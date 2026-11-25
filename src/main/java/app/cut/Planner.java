package app.cut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Deterministic best-fit decreasing against physical stock. Lengths are tenths of a millimetre. */
final class Planner {
    record Blank(long id, long profileId, long length, boolean offcut) {}
    record Demand(long id, long profileId, long length, int count) {}
    record Cut(long demandId, long length) {}
    record Bar(long stockId, long profileId, long inputLength, List<Cut> cuts, long remainder, boolean reusable) {}
    record Shortage(long demandId, long profileId, long length, int count) {}
    record Result(List<Bar> bars, List<Shortage> shortages) {}

    private record Work(Blank blank, List<Cut> cuts, long remainder) {
        Work cut(Demand demand, long cost) {
            cuts.add(new Cut(demand.id(), demand.length()));
            return new Work(blank, cuts, remainder - cost);
        }
    }

    private static final Comparator<Work> CLOSEST = Comparator.comparingLong(Work::remainder)
        .thenComparing(work -> !work.blank().offcut()).thenComparingLong(work -> work.blank().id());
    private static final Comparator<Demand> LONGEST = Comparator.comparingLong(Demand::length).reversed().thenComparingLong(Demand::id);

    private Planner() {}

    static Result plan(List<Blank> stock, List<Demand> demands, long kerf, long trim, long reusable) {
        if (kerf < 0 || trim < 0 || reusable < 1 || kerf > CutInput.MAX_LENGTH ||
                trim > CutInput.MAX_LENGTH || reusable > CutInput.MAX_LENGTH || stock.size() > CutInput.MAX_STOCK) {
            throw CutInput.invalid("Проверьте припуски, длину остатка и число заготовок.");
        }
        var blankIds = new HashSet<Long>();
        var demandIds = new HashSet<Long>();
        for (var blank : stock) {
            if (!blankIds.add(blank.id()) || blank.length() <= 0 || blank.length() > CutInput.MAX_LENGTH) {
                throw CutInput.invalid("Некорректная исходная заготовка.");
            }
        }
        long count = 0;
        for (var demand : demands) {
            if (!demandIds.add(demand.id()) || demand.length() <= 0 || demand.length() > CutInput.MAX_LENGTH || demand.count() < 1) {
                throw CutInput.invalid("Некорректная позиция заказа.");
            }
            count += demand.count();
        }
        if (count > CutInput.MAX_PIECES) throw CutInput.invalid("В одном расчёте допускается до 2 000 деталей.");

        var byStock = stock.stream().collect(Collectors.groupingBy(Blank::profileId));
        var byDemand = demands.stream().collect(Collectors.groupingBy(Demand::profileId, TreeMap::new, Collectors.toList()));
        var bars = new ArrayList<Bar>();
        var missing = new ArrayList<Shortage>();
        for (Map.Entry<Long, List<Demand>> group : byDemand.entrySet()) {
            var blanks = byStock.getOrDefault(group.getKey(), List.of());
            var lines = group.getValue().stream().sorted(LONGEST).toList();
            var exactFit = pack(blanks, lines, kerf, trim, reusable, false);
            var reuseFirst = pack(blanks, lines, kerf, trim, reusable, true);
            var selected = compare(exactFit, reuseFirst) <= 0 ? exactFit : reuseFirst;
            bars.addAll(selected.bars());
            missing.addAll(selected.shortages());
        }
        return new Result(List.copyOf(bars), List.copyOf(missing));
    }

    private static Result pack(List<Blank> stock, List<Demand> demands, long kerf, long trim, long reusable, boolean preferOffcuts) {
        var used = new TreeSet<>(CLOSEST);
        var full = new TreeSet<>(CLOSEST);
        var ends = new TreeSet<>(CLOSEST);
        for (var blank : stock) {
            long capacity = blank.length() - 2 * trim;
            if (capacity > 0) (blank.offcut() ? ends : full).add(new Work(blank, new ArrayList<>(), capacity));
        }

        var missing = new ArrayList<Shortage>();
        for (var demand : demands) {
            long cost = demand.length() + kerf;
            var probe = new Work(new Blank(Long.MIN_VALUE, demand.profileId(), 1, true), List.of(), cost);
            int remaining = demand.count();
            for (; remaining > 0; remaining--) {
                var current = used.ceiling(probe);
                if (current != null) {
                    used.remove(current);
                } else {
                    var blank = full.ceiling(probe);
                    var end = ends.ceiling(probe);
                    if (end != null && (preferOffcuts || blank == null || CLOSEST.compare(end, blank) <= 0)) {
                        current = end;
                        ends.remove(end);
                    } else if (blank != null) {
                        current = blank;
                        full.remove(blank);
                    } else break;
                }
                used.add(current.cut(demand, cost));
            }
            if (remaining > 0) missing.add(new Shortage(demand.id(), demand.profileId(), demand.length(), remaining));
        }

        var bars = used.stream().sorted(Comparator.comparingLong(work -> work.blank().id())).map(work ->
            new Bar(work.blank().id(), work.blank().profileId(), work.blank().length(), List.copyOf(work.cuts()),
                work.remainder(), work.remainder() >= reusable)).toList();
        return new Result(bars, List.copyOf(missing));
    }

    private static int compare(Result a, Result b) {
        int compare = Integer.compare(a.shortages().stream().mapToInt(Shortage::count).sum(), b.shortages().stream().mapToInt(Shortage::count).sum());
        if (compare != 0) return compare;
        compare = Long.compare(a.shortages().stream().mapToLong(s -> s.length() * s.count()).sum(),
            b.shortages().stream().mapToLong(s -> s.length() * s.count()).sum());
        if (compare != 0) return compare;
        compare = Integer.compare(a.bars().size(), b.bars().size());
        if (compare != 0) return compare;
        return Long.compare(a.bars().stream().filter(bar -> !bar.reusable()).mapToLong(Bar::remainder).sum(),
            b.bars().stream().filter(bar -> !bar.reusable()).mapToLong(Bar::remainder).sum());
    }
}
