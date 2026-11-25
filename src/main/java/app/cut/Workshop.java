package app.cut;

import static app.cut.CutInput.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Workshop {
    record Profile(long id, String code, String name) {}
    record ProfileInput(String code, String name) {}
    record Stock(long id, long profileId, long length, String kind, String state, Long originId, Long reservedPlan, long version) {}
    record StockInput(Long profileId, String length, Integer quantity, String kind) {}
    record Line(long id, long orderId, long profileId, long length, int quantity, int produced, int held) {
        int available() { return quantity - produced - held; }
    }
    record LineInput(Long profileId, String length, Integer quantity) {}
    record Order(long id, String name, String customer, OffsetDateTime createdAt, List<Line> lines) {}
    record OrderInput(String name, String customer, List<LineInput> lines) {}
    record PlanInput(List<Long> orderIds, String kerf, String trim, String reusable) {}
    record Plan(long id, String state, long kerf, long trim, long reusable, OffsetDateTime createdAt, OffsetDateTime confirmedAt) {}
    record PlanSummary(long id, String state, int orders, int bars, int pieces, int shortage, OffsetDateTime createdAt) {}
    record BarCut(long lineId, long orderId, String orderName, long length, int quantity, Integer actual) {}
    record PlanBar(long id, long stockId, long profileId, String code, String profile, String kind, long inputLength,
                   long expected, Long actual, Long offcutId, long version, List<BarCut> cuts) {}
    record Missing(long lineId, long orderId, String orderName, String code, long length, int quantity) {}
    record PlanDetails(Plan plan, List<Long> orderIds, List<PlanBar> bars, List<Missing> shortages) {}
    record FactInput(List<ActualBarInput> bars) {}
    record ActualBarInput(Long id, String remainder, List<ActualCutInput> cuts) {}
    record ActualCutInput(Long lineId, Integer quantity) {}
    record Journal(long id, String category, String detail, OffsetDateTime at) {}
    record Snapshot(List<Profile> profiles, List<Stock> stock, List<Order> orders, List<PlanSummary> plans, List<Journal> journal) {}

    private record ActualBar(PlanBar bar, long remainder, int count, Map<Long, Integer> amounts) {}
    private record ValidatedLine(long profileId, long length, int quantity) {}
    private static final RowMapper<Profile> PROFILE = (rs, i) -> new Profile(rs.getLong("id"), rs.getString("code"), rs.getString("name"));
    private static final RowMapper<Stock> STOCK = (rs, i) -> new Stock(rs.getLong("id"), rs.getLong("profile_id"), rs.getLong("length"), rs.getString("kind"),
        rs.getString("state"), rs.getObject("origin_id", Long.class), rs.getObject("reserved_plan", Long.class), rs.getLong("version"));
    private static final RowMapper<Line> LINE = (rs, i) -> new Line(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("profile_id"), rs.getLong("length"),
        rs.getInt("quantity"), rs.getInt("produced"), rs.getInt("held"));
    private static final RowMapper<Plan> PLAN = (rs, i) -> new Plan(rs.getLong("id"), rs.getString("state"), rs.getLong("kerf"), rs.getLong("trim"),
        rs.getLong("reusable"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("confirmed_at", OffsetDateTime.class));

    private final JdbcTemplate db;

    Workshop(JdbcTemplate db) { this.db = db; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot() {
        var profiles = db.query("SELECT * FROM profile ORDER BY code", PROFILE);
        var stock = db.query("SELECT * FROM stock WHERE state <> 'CONSUMED' ORDER BY profile_id, length DESC, id", STOCK);
        var byOrder = db.query("SELECT * FROM order_line ORDER BY id", LINE).stream().collect(Collectors.groupingBy(Line::orderId));
        var orders = db.query("SELECT * FROM customer_order ORDER BY id DESC", (rs, i) ->
            new Order(rs.getLong("id"), rs.getString("name"), rs.getString("customer"), rs.getObject("created_at", OffsetDateTime.class),
                byOrder.getOrDefault(rs.getLong("id"), List.of())));
        var plans = db.query("""
            SELECT p.id,p.state,p.created_at,
              (SELECT COUNT(*) FROM plan_order o WHERE o.plan_id=p.id) AS orders_count,
              (SELECT COUNT(*) FROM plan_bar b WHERE b.plan_id=p.id) AS bars_count,
              (SELECT COALESCE(SUM(c.quantity),0) FROM plan_cut c JOIN plan_bar b ON b.id=c.bar_id WHERE b.plan_id=p.id) AS pieces,
              (SELECT COALESCE(SUM(q.quantity),0) FROM shortage q WHERE q.plan_id=p.id) AS missing
            FROM cut_plan p ORDER BY p.id DESC
            """, (rs, i) -> new PlanSummary(rs.getLong("id"), rs.getString("state"), rs.getInt("orders_count"), rs.getInt("bars_count"),
                rs.getInt("pieces"), rs.getInt("missing"), rs.getObject("created_at", OffsetDateTime.class)));
        var journal = db.query("SELECT * FROM journal ORDER BY id DESC LIMIT 300", (rs, i) ->
            new Journal(rs.getLong("id"), rs.getString("category"), rs.getString("detail"), rs.getObject("happened_at", OffsetDateTime.class)));
        return new Snapshot(profiles, stock, orders, plans, journal);
    }

    @Transactional
    public Profile addProfile(ProfileInput input) {
        if (input == null) throw invalid("Укажите профиль.");
        var code = code(input.code());
        var name = required(input.name(), 100, "Название профиля");
        lock();
        if (db.queryForObject("SELECT COUNT(*) FROM profile WHERE code=?", Integer.class, code) > 0) {
            throw conflict("Профиль с таким кодом уже существует.");
        }
        long id = insert("INSERT INTO profile(code,name) VALUES (?,?)", code, name);
        log("PROFILE", "Добавлен профиль " + code);
        return new Profile(id, code, name);
    }

    @Transactional
    public int addStock(StockInput input) {
        if (input == null) throw invalid("Укажите заготовки.");
        long profileId = id(input.profileId(), "профиль");
        long length = length(input.length(), "Длину", false);
        int count = quantity(input.quantity(), 1000, "Количество");
        if (!"FULL".equals(input.kind()) && !"OFFCUT".equals(input.kind())) throw invalid("Выберите вид заготовки.");
        lock();
        var profile = profile(profileId);
        int current = db.queryForObject("SELECT COUNT(*) FROM stock WHERE state <> 'CONSUMED'", Integer.class);
        if (current + count > MAX_STOCK) throw invalid("На складе может быть до 10 000 доступных и зарезервированных заготовок.");
        var values = new ArrayList<Object[]>(count);
        for (int index = 0; index < count; index++) values.add(new Object[] {profileId, length, input.kind()});
        db.batchUpdate("INSERT INTO stock(profile_id,length,kind) VALUES (?,?,?)", values);
        log("STOCK", "Принято на склад " + profile.code() + " · " + count + " шт. × " + mm(length) + " мм");
        return count;
    }

    @Transactional
    public long addOrder(OrderInput input) {
        lock();
        return addOrderLocked(input);
    }

    private long addOrderLocked(OrderInput input) {
        if (input == null) throw invalid("Укажите заказ.");
        var name = required(input.name(), 120, "Название заказа");
        var customer = optional(input.customer(), 100, "Заказчик");
        if (input.lines() == null || input.lines().isEmpty() || input.lines().size() > 200) {
            throw invalid("Добавьте от 1 до 200 позиций заказа.");
        }
        var profiles = new HashSet<Long>();
        var lines = new ArrayList<ValidatedLine>();
        int pieces = 0;
        for (var line : input.lines()) {
            if (line == null) throw invalid("Проверьте строки заказа.");
            long profile = id(line.profileId(), "профиль");
            if (profiles.add(profile)) profile(profile);
            int quantity = quantity(line.quantity(), MAX_PIECES, "Количество");
            pieces += quantity;
            if (pieces > MAX_PIECES) throw invalid("В заказе может быть до 2 000 деталей.");
            lines.add(new ValidatedLine(profile, length(line.length(), "Длину", false), quantity));
        }
        long order = insert("INSERT INTO customer_order(name,customer) VALUES (?,?)", name, customer);
        db.batchUpdate("INSERT INTO order_line(order_id,profile_id,length,quantity) VALUES (?,?,?,?)",
            lines.stream().map(line -> new Object[]{order, line.profileId(), line.length(), line.quantity()}).toList());
        log("ORDER", "Заказ №" + order + " · " + name + " · " + pieces + " деталей");
        return order;
    }

    @Transactional
    public long importOrder(String name, String customer, byte[] bytes) {
        lock();
        var profiles = db.query("SELECT * FROM profile", PROFILE).stream().collect(Collectors.toMap(Profile::code, Profile::id));
        var lines = OrderCsv.parse(bytes, profiles);
        return addOrderLocked(new OrderInput(name, customer, lines));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PlanDetails calculate(PlanInput input) {
        if (input == null || input.orderIds() == null || input.orderIds().isEmpty() || input.orderIds().size() > MAX_SELECTED_ORDERS
                || new HashSet<>(input.orderIds()).size() != input.orderIds().size()) {
            throw invalid("Выберите от 1 до 30 разных заказов.");
        }
        long kerf = length(input.kerf(), "Ширину пропила", true);
        long trim = length(input.trim(), "Торцовку на один конец", true);
        long reusable = length(input.reusable(), "Минимум пригодного остатка", false);
        var demands = new ArrayList<Planner.Demand>();
        for (Long order : input.orderIds()) {
            id(order, "заказ");
            if (db.queryForObject("SELECT COUNT(*) FROM customer_order WHERE id=?", Integer.class, order) == 0) {
                throw missing("Один из заказов не найден.");
            }
            var lines = db.query("SELECT * FROM order_line WHERE order_id=? ORDER BY id", LINE, order);
            for (var line : lines) {
                if (line.available() > 0) demands.add(new Planner.Demand(line.id(), line.profileId(), line.length(), line.available()));
            }
        }
        if (demands.isEmpty()) throw conflict("По выбранным заказам нет деталей без резерва.");
        var stock = db.query("SELECT * FROM stock WHERE state='AVAILABLE'", STOCK);
        var index = stock.stream().collect(Collectors.toMap(Stock::id, Stock::version));
        var blanks = stock.stream().map(item -> new Planner.Blank(item.id(), item.profileId(), item.length(), item.kind().equals("OFFCUT"))).toList();
        var result = Planner.plan(blanks, demands, kerf, trim, reusable);
        long plan = insert("INSERT INTO cut_plan(kerf,trim,reusable) VALUES (?,?,?)", kerf, trim, reusable);
        db.batchUpdate("INSERT INTO plan_order(plan_id,order_id) VALUES (?,?)",
            input.orderIds().stream().map(order -> new Object[] {plan, order}).toList());
        var cuts = new ArrayList<Object[]>();
        for (var bar : result.bars()) {
            long row = insert("INSERT INTO plan_bar(plan_id,stock_id,stock_version,expected) VALUES (?,?,?,?)",
                plan, bar.stockId(), index.get(bar.stockId()), bar.remainder());
            var grouped = bar.cuts().stream().collect(Collectors.groupingBy(Planner.Cut::demandId, LinkedHashMap::new, Collectors.counting()));
            grouped.forEach((line, amount) -> cuts.add(new Object[] {row, line, amount.intValue()}));
        }
        db.batchUpdate("INSERT INTO plan_cut(bar_id,line_id,quantity) VALUES (?,?,?)", cuts);
        db.batchUpdate("INSERT INTO shortage(plan_id,line_id,quantity) VALUES (?,?,?)",
            result.shortages().stream().map(item -> new Object[]{plan, item.demandId(), item.count()}).toList());
        log("PLAN", "Рассчитан план №" + plan + " · " + result.bars().size() + " заготовок");
        return details(plan);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlanDetails plan(long id) { return details(id); }

    @Transactional
    public PlanDetails reserve(long id) {
        lock();
        var detail = details(id);
        if (detail.plan().state().equals("RESERVED")) return detail;
        if (!detail.plan().state().equals("DRAFT")) throw conflict("Резерв доступен только для нового плана.");
        if (detail.bars().isEmpty()) throw conflict("В плане нет резов, которые можно зарезервировать.");
        for (var bar : detail.bars()) {
            int changed = db.update("""
                UPDATE stock SET state='RESERVED', reserved_plan=?, version=version+1
                WHERE id=? AND state='AVAILABLE' AND version=?
                """, id, bar.stockId(), bar.version());
            if (changed != 1) throw conflict("Склад изменился после расчёта. Создайте новый план.");
        }
        for (var entry : reservedCounts(detail.bars()).entrySet()) {
            int changed = db.update("UPDATE order_line SET held=held+? WHERE id=? AND quantity-produced-held>=?",
                entry.getValue(), entry.getKey(), entry.getValue());
            if (changed != 1) throw conflict("Другой план занял детали этого заказа. Пересчитайте раскрой.");
        }
        db.update("UPDATE cut_plan SET state='RESERVED' WHERE id=?", id);
        log("RESERVE", "Зарезервирован план №" + id + " · " + detail.bars().size() + " заготовок");
        return details(id);
    }

    @Transactional
    public PlanDetails cancel(long id) {
        lock();
        var detail = details(id);
        if (detail.plan().state().equals("CANCELLED")) return detail;
        if (detail.plan().state().equals("APPLIED")) throw conflict("Подтверждённый раскрой отменить нельзя.");
        if (detail.plan().state().equals("RESERVED")) {
            db.update("UPDATE stock SET state='AVAILABLE',reserved_plan=NULL,version=version+1 WHERE reserved_plan=?", id);
            releaseLines(detail.bars(), Map.of());
        }
        db.update("UPDATE cut_plan SET state='CANCELLED' WHERE id=?", id);
        log("CANCEL", "Отменён план №" + id);
        return details(id);
    }

    @Transactional
    public PlanDetails confirm(long id, FactInput input) {
        lock();
        var detail = details(id);
        if (!detail.plan().state().equals("RESERVED") && !detail.plan().state().equals("APPLIED")) {
            throw conflict("Для подтверждения сначала зарезервируйте план.");
        }
        var actual = validateFact(detail, input);
        var signature = fingerprint(actual);
        if (detail.plan().state().equals("APPLIED")) {
            var saved = db.queryForObject("SELECT actual_hash FROM cut_plan WHERE id=?", String.class, id);
            if (Objects.equals(saved, signature)) return detail;
            throw conflict("По этому плану уже подтверждены другие фактические резы.");
        }

        var produced = new TreeMap<Long, Integer>();
        int pieces = 0;
        int used = 0;
        int kept = 0;
        for (var fact : actual) {
            var bar = fact.bar();
            Long offcutId = null;
            if (fact.count() > 0) {
                int changed = db.update("""
                    UPDATE stock SET state='CONSUMED',reserved_plan=NULL,version=version+1
                    WHERE id=? AND state='RESERVED' AND reserved_plan=?
                    """, bar.stockId(), id);
                if (changed != 1) throw conflict("Резерв на заготовку изменился.");
                used++;
                if (fact.remainder() >= detail.plan().reusable()) {
                    offcutId = insert("INSERT INTO stock(profile_id,length,kind,origin_id) VALUES (?,?,'OFFCUT',?)",
                        bar.profileId(), fact.remainder(), bar.stockId());
                    kept++;
                }
            } else {
                int changed = db.update("""
                    UPDATE stock SET state='AVAILABLE',reserved_plan=NULL,version=version+1
                    WHERE id=? AND state='RESERVED' AND reserved_plan=?
                    """, bar.stockId(), id);
                if (changed != 1) throw conflict("Резерв на заготовку изменился.");
            }
            db.update("UPDATE plan_bar SET actual=?,offcut_id=? WHERE id=?", fact.remainder(), offcutId, bar.id());
            for (var cut : fact.amounts().entrySet()) {
                db.update("UPDATE plan_cut SET actual=? WHERE bar_id=? AND line_id=?", cut.getValue(), bar.id(), cut.getKey());
                produced.merge(cut.getKey(), cut.getValue(), Integer::sum);
            }
            pieces += fact.count();
        }
        releaseLines(detail.bars(), produced);
        db.update("UPDATE cut_plan SET state='APPLIED',confirmed_at=CURRENT_TIMESTAMP,actual_hash=? WHERE id=?", signature, id);
        log("FACT", "Подтверждён план №" + id + " · " + pieces + " деталей · " + used + " заготовок · " + kept + " обрезков на склад");
        return details(id);
    }

    private List<ActualBar> validateFact(PlanDetails detail, FactInput input) {
        if (input == null || input.bars() == null || input.bars().size() != detail.bars().size()) {
            throw invalid("Заполните фактические резы для каждой заготовки.");
        }
        var supplied = new HashMap<Long, ActualBarInput>();
        for (var bar : input.bars()) {
            if (bar == null || bar.id() == null || supplied.putIfAbsent(bar.id(), bar) != null) {
                throw invalid("Фактические заготовки должны быть уникальными.");
            }
        }
        var result = new ArrayList<ActualBar>();
        for (var bar : detail.bars()) {
            var choice = supplied.get(bar.id());
            if (choice == null || choice.cuts() == null || choice.cuts().size() != bar.cuts().size()) {
                throw invalid("Для заготовки №" + bar.stockId() + " заполните все строки.");
            }
            var counts = new TreeMap<Long, Integer>();
            for (var cut : choice.cuts()) {
                if (cut == null || cut.lineId() == null || cut.quantity() == null || counts.putIfAbsent(cut.lineId(), cut.quantity()) != null) {
                    throw invalid("Строки с фактическими резами должны быть уникальны.");
                }
            }
            long consumed = 0;
            int pieces = 0;
            for (var cut : bar.cuts()) {
                var count = counts.get(cut.lineId());
                if (count == null || count < 0 || count > cut.quantity()) {
                    throw invalid("Фактическое количество должно быть от нуля до запланированного.");
                }
                consumed += (cut.length() + detail.plan().kerf()) * count;
                pieces += count;
            }
            long maximum = pieces == 0 ? bar.inputLength() : bar.inputLength() - 2 * detail.plan().trim() - consumed;
            long remainder = choice.remainder() == null || choice.remainder().isBlank()
                ? maximum : length(choice.remainder(), "Измеренный остаток", true);
            if (remainder > maximum || maximum < 0) {
                throw invalid("Остаток заготовки №" + bar.stockId() + " не может превышать " + mm(Math.max(0, maximum)) + " мм.");
            }
            if (pieces == 0 && remainder != maximum) {
                throw invalid("При нуле резов исходная заготовка возвращается целиком.");
            }
            result.add(new ActualBar(bar, remainder, pieces, counts));
        }
        return result;
    }

    private void releaseLines(List<PlanBar> bars, Map<Long, Integer> produced) {
        for (var entry : reservedCounts(bars).entrySet()) {
            int changed = db.update("""
                UPDATE order_line SET held=held-?,produced=produced+?
                WHERE id=? AND held>=?
                """, entry.getValue(), produced.getOrDefault(entry.getKey(), 0), entry.getKey(), entry.getValue());
            if (changed != 1) throw conflict("Резерв по заказу изменился.");
        }
    }

    private Map<Long, Integer> reservedCounts(List<PlanBar> bars) {
        var totals = new TreeMap<Long, Integer>();
        for (var bar : bars) for (var cut : bar.cuts()) totals.merge(cut.lineId(), cut.quantity(), Integer::sum);
        return totals;
    }

    private PlanDetails details(long id) {
        var plan = db.query("SELECT * FROM cut_plan WHERE id=?", PLAN, id).stream().findFirst()
            .orElseThrow(() -> missing("План не найден."));
        var orders = db.queryForList("SELECT order_id FROM plan_order WHERE plan_id=? ORDER BY order_id", Long.class, id);
        var allCuts = db.query("""
            SELECT c.bar_id,c.line_id,c.quantity,c.actual,l.order_id,l.length,o.name
            FROM plan_cut c JOIN plan_bar b ON b.id=c.bar_id
            JOIN order_line l ON l.id=c.line_id JOIN customer_order o ON o.id=l.order_id
            WHERE b.plan_id=? ORDER BY c.bar_id,l.length DESC,c.line_id
            """, rs -> {
                var index = new HashMap<Long, List<BarCut>>();
                while (rs.next()) {
                    index.computeIfAbsent(rs.getLong("bar_id"), ignored -> new ArrayList<>()).add(
                        new BarCut(rs.getLong("line_id"), rs.getLong("order_id"), rs.getString("name"), rs.getLong("length"),
                            rs.getInt("quantity"), rs.getObject("actual", Integer.class)));
                }
                return index;
            }, id);
        var bars = db.query("""
            SELECT b.*,s.profile_id,s.length,s.kind,p.code,p.name FROM plan_bar b
            JOIN stock s ON s.id=b.stock_id JOIN profile p ON p.id=s.profile_id
            WHERE b.plan_id=? ORDER BY p.code,b.id
            """, (rs, i) -> new PlanBar(rs.getLong("id"), rs.getLong("stock_id"), rs.getLong("profile_id"), rs.getString("code"),
                rs.getString("name"), rs.getString("kind"), rs.getLong("length"), rs.getLong("expected"),
                rs.getObject("actual", Long.class), rs.getObject("offcut_id", Long.class),
                rs.getLong("stock_version"), allCuts.getOrDefault(rs.getLong("id"), List.of())), id);
        var shortages = db.query("""
            SELECT s.line_id,s.quantity,l.order_id,l.length,o.name,p.code
            FROM shortage s JOIN order_line l ON l.id=s.line_id JOIN customer_order o ON o.id=l.order_id
            JOIN profile p ON p.id=l.profile_id WHERE s.plan_id=? ORDER BY p.code,l.length DESC,s.line_id
            """, (rs, i) -> new Missing(rs.getLong("line_id"), rs.getLong("order_id"), rs.getString("name"), rs.getString("code"),
                rs.getLong("length"), rs.getInt("quantity")), id);
        return new PlanDetails(plan, orders, bars, shortages);
    }

    private static String fingerprint(List<ActualBar> actual) {
        var key = new StringBuilder();
        for (var fact : actual) {
            key.append(fact.bar().id()).append(':').append(fact.remainder()).append(':');
            fact.amounts().forEach((line, count) -> key.append(line).append('=').append(count).append(','));
            key.append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Transactional
    public void demo() {
        if (!populateDemo()) {
            throw conflict("Демо-данные можно добавить только в пустой справочник.");
        }
    }

    @Transactional
    public void seedDemoIfEmpty() {
        populateDemo();
    }

    private boolean populateDemo() {
        lock();
        if (db.queryForObject("SELECT COUNT(*) FROM profile", Integer.class) != 0) return false;
        long rectangular = insert("INSERT INTO profile(code,name) VALUES (?,?)", "T40", "Труба стальная 40 × 20 × 2 мм");
        long square = insert("INSERT INTO profile(code,name) VALUES (?,?)", "T25", "Труба стальная 25 × 25 × 1,5 мм");
        long aluminium = insert("INSERT INTO profile(code,name) VALUES (?,?)", "A25", "Профиль алюминиевый 25 × 25 мм");
        demoStock(rectangular, "FULL", 6000, 6000, 6000);
        demoStock(rectangular, "OFFCUT", 1850, 1280);
        demoStock(square, "FULL", 6000, 6000);
        demoStock(square, "OFFCUT", 2200);
        demoStock(aluminium, "FULL", 3000, 3000);
        demoStock(aluminium, "OFFCUT", 1280);
        addOrderLocked(new OrderInput("Стеллажи для архива", "Северный склад (учебный)", List.of(
            new LineInput(rectangular, "1835", 4), new LineInput(rectangular, "945", 6),
            new LineInput(square, "420", 6))));
        addOrderLocked(new OrderInput("Рамы для витрин", "Витрина 24 (учебный)", List.of(
            new LineInput(aluminium, "1720", 4), new LineInput(aluminium, "890", 2))));
        addOrderLocked(new OrderInput("Секции перегородки", "Мастерская № 7 (учебный)", List.of(
            new LineInput(aluminium, "2150", 2), new LineInput(aluminium, "720", 4))));
        addOrderLocked(new OrderInput("Ремонт ячеек хранения", "Северный склад (учебный)", List.of(
            new LineInput(square, "1600", 4), new LineInput(rectangular, "1050", 2))));
        log("DEMO", "Загружены 3 профиля, 11 заготовок и 4 учебных заказа");
        return true;
    }

    private void demoStock(long profileId, String kind, long... millimetres) {
        var rows = Arrays.stream(millimetres)
            .mapToObj(size -> new Object[]{profileId, size * 10, kind})
            .toList();
        db.batchUpdate("INSERT INTO stock(profile_id,length,kind) VALUES (?,?,?)", rows);
    }

    private Profile profile(long id) {
        return db.query("SELECT * FROM profile WHERE id=?", PROFILE, id).stream().findFirst()
            .orElseThrow(() -> missing("Профиль не найден."));
    }

    private static long id(Long value, String name) {
        if (value == null || value < 1) throw invalid("Выберите " + name + ".");
        return value;
    }

    private void lock() { db.queryForObject("SELECT id FROM workshop_lock WHERE id=1 FOR UPDATE", Integer.class); }

    private void log(String category, String detail) {
        db.update("INSERT INTO journal(category,detail) VALUES (?,?)", category, detail);
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        var result = key.getKeys();
        if (result == null || !(result.get("ID") instanceof Number id)) throw new IllegalStateException("Missing identity");
        return id.longValue();
    }
}
