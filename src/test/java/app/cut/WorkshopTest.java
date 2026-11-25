package app.cut;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.datasource.url=jdbc:h2:mem:optimizator_test;DB_CLOSE_DELAY=-1")
class WorkshopTest {
    @Autowired Workshop workshop;
    @Autowired JdbcTemplate db;

    @BeforeEach
    void clear() {
        for (String table : List.of("journal","shortage","plan_cut","plan_bar","plan_order","stock","cut_plan",
                "order_line","customer_order","profile")) db.update("DELETE FROM " + table);
    }

    @Test
    void reservesAndAppliesActualExactlyOnce() {
        var profile = workshop.addProfile(new Workshop.ProfileInput("T40", "Труба"));
        workshop.addStock(new Workshop.StockInput(profile.id(), "6000", 2, "FULL"));
        long order = workshop.addOrder(new Workshop.OrderInput("Заказ", "", List.of(new Workshop.LineInput(profile.id(), "2000", 2))));
        var plan = workshop.calculate(new Workshop.PlanInput(List.of(order), "3", "5", "250"));
        var competitor = workshop.calculate(new Workshop.PlanInput(List.of(order), "3", "5", "250"));
        assertEquals(19840, plan.bars().getFirst().expected());
        var reserved = workshop.reserve(plan.plan().id());
        assertEquals("RESERVED", reserved.plan().state());
        assertEquals("RESERVED", workshop.reserve(plan.plan().id()).plan().state());
        assertThrows(CutInput.Problem.class, () -> workshop.reserve(competitor.plan().id()));

        var bar = reserved.bars().getFirst();
        var line = bar.cuts().getFirst();
        var impossible = new Workshop.FactInput(List.of(new Workshop.ActualBarInput(bar.id(), "3988", List.of(new Workshop.ActualCutInput(line.lineId(), 1)))));
        assertThrows(CutInput.Problem.class, () -> workshop.confirm(plan.plan().id(), impossible));
        assertEquals("RESERVED", workshop.plan(plan.plan().id()).plan().state());

        var actual = new Workshop.FactInput(List.of(new Workshop.ActualBarInput(bar.id(), "3980", List.of(new Workshop.ActualCutInput(line.lineId(), 1)))));
        var applied = workshop.confirm(plan.plan().id(), actual);
        assertEquals("APPLIED", applied.plan().state());
        assertEquals(39800, applied.bars().getFirst().actual());
        var repeated = workshop.confirm(plan.plan().id(), actual);
        assertEquals(applied.bars().getFirst().offcutId(), repeated.bars().getFirst().offcutId());
        var view = workshop.snapshot();
        assertEquals(2, view.stock().size());
        assertEquals(1, view.stock().stream().filter(item -> item.kind().equals("OFFCUT")).count());
        assertEquals(1, view.orders().getFirst().lines().getFirst().produced());
        assertEquals(0, view.orders().getFirst().lines().getFirst().held());
        assertThrows(CutInput.Problem.class, () -> workshop.cancel(plan.plan().id()));
    }

    @Test
    void concurrentPlansCannotReserveTheSameMaterial() throws Exception {
        var profile = workshop.addProfile(new Workshop.ProfileInput("X", "Профиль"));
        workshop.addStock(new Workshop.StockInput(profile.id(), "3000", 1, "OFFCUT"));
        long first = workshop.addOrder(new Workshop.OrderInput("A", "", List.of(new Workshop.LineInput(profile.id(), "1000", 1))));
        long second = workshop.addOrder(new Workshop.OrderInput("B", "", List.of(new Workshop.LineInput(profile.id(), "1000", 1))));
        long a = workshop.calculate(new Workshop.PlanInput(List.of(first), "3", "0", "100")).plan().id();
        long b = workshop.calculate(new Workshop.PlanInput(List.of(second), "3", "0", "100")).plan().id();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var x = workers.submit(() -> tryReserve(a, start));
            var y = workers.submit(() -> tryReserve(b, start));
            start.countDown();
            assertNotEquals(x.get(8, TimeUnit.SECONDS), y.get(8, TimeUnit.SECONDS));
        }
        var winner = workshop.snapshot().plans().stream().filter(plan -> plan.state().equals("RESERVED")).findFirst().orElseThrow();
        workshop.cancel(winner.id());
        assertEquals("AVAILABLE", workshop.snapshot().stock().getFirst().state());
        assertEquals(0, workshop.snapshot().orders().stream().flatMap(order -> order.lines().stream()).mapToInt(Workshop.Line::held).sum());
    }

    private boolean tryReserve(long id, CountDownLatch start) throws InterruptedException {
        start.await();
        try { workshop.reserve(id); return true; }
        catch (CutInput.Problem expected) { return false; }
    }

    @Test
    void demoIsReproducibleAndDoesNotModifyExistingData() {
        workshop.seedDemoIfEmpty();
        var seed = workshop.snapshot();
        assertEquals(3, seed.profiles().size());
        assertEquals(11, seed.stock().size());
        assertEquals(4, seed.orders().size());
        assertEquals(4, seed.stock().stream().filter(stock -> stock.kind().equals("OFFCUT")).count());

        workshop.addProfile(new Workshop.ProfileInput("CUSTOM", "Рабочий профиль"));
        var edited = workshop.snapshot();
        workshop.seedDemoIfEmpty();
        assertEquals(edited, workshop.snapshot());
        assertThrows(CutInput.Problem.class, () -> workshop.demo());
        assertEquals(edited, workshop.snapshot());
    }

    @Test
    void readsExcelCsvAndRejectsUnknownProfilesAtomically() {
        workshop.addProfile(new Workshop.ProfileInput("T40", "Труба"));
        var text = "Профиль;Длина;Количество\r\n\"T40\";1234,5;2";
        long order = workshop.importOrder("Импорт", "Клиент", text.getBytes(Charset.forName("windows-1251")));
        var line = workshop.snapshot().orders().stream().filter(item -> item.id() == order).findFirst().orElseThrow().lines().getFirst();
        assertEquals(12345, line.length());
        assertEquals(2, line.quantity());
        var invalid = "Профиль;Длина;Количество\nT40;11;1\nBAD;12;1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(CutInput.Problem.class, () -> workshop.importOrder("Неверный", "", invalid));
        assertEquals(1, workshop.snapshot().orders().size());
    }
}
