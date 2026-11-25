package app.cut;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlannerTest {
    @Test
    void exactFitChargesEveryKerfAndBothEndTrims() {
        var stock = new Planner.Blank(1, 4, 1000, false);
        var demand = new Planner.Demand(10, 4, 470, 2);
        var plan = Planner.plan(List.of(stock), List.of(demand), 10, 20, 80);
        var bar = plan.bars().getFirst();
        assertEquals(2, bar.cuts().size());
        assertEquals(0, bar.remainder());
        assertFalse(bar.reusable());
        assertTrue(plan.shortages().isEmpty());
        var shortPlan = Planner.plan(List.of(new Planner.Blank(1, 4, 999, false)), List.of(demand), 10, 20, 80);
        assertEquals(1, shortPlan.shortages().getFirst().count());
    }

    @Test
    void neverMixesProfilesOrReusesPhysicalStock() {
        var blanks = List.of(new Planner.Blank(1, 4, 1100, true), new Planner.Blank(2, 4, 2200, false),
            new Planner.Blank(3, 5, 1200, false));
        var orders = List.of(new Planner.Demand(10, 4, 700, 2), new Planner.Demand(11, 5, 1100, 1),
            new Planner.Demand(12, 9, 100, 3));
        var plan = Planner.plan(blanks, orders, 25, 20, 100);
        var used = new HashSet<Long>();
        for (var bar : plan.bars()) {
            assertTrue(used.add(bar.stockId()));
            assertTrue(bar.cuts().stream().allMatch(cut -> cut.demandId() == (bar.profileId() == 4 ? 10 : 11)));
            assertEquals(bar.inputLength() - 40 - bar.cuts().stream().mapToLong(cut -> cut.length() + 25).sum(), bar.remainder());
        }
        assertEquals(List.of(new Planner.Shortage(12, 9, 100, 3)), plan.shortages());
    }
}
