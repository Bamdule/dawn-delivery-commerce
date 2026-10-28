package io.github.bamdule.dawn.fulfillment.allocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 고정한 입력으로 계산만 한다. 같은 입력이면 결과가 늘 같다 (docs/design/05 "2. 할당").
// 1차는 권역 안의 한 센터에서 전량 나가는 주문만(그중 재고가 가장 많이 남은 센터), 2차는 권역 안 여러 센터로 나눠, 3차는 새벽 도착이 되는 권역 밖 센터로 채운다
public final class Allocator {

    static final String REGION_MISMATCH = "REGION_MISMATCH";
    static final String STOCKTAKE_GAP = "STOCKTAKE_GAP";

    private Allocator() {
    }

    // orders 는 paid_at, 주문번호 순이어야 한다. 먼저 결제한 주문이 먼저 재고를 받는다
    public static Plan allocate(List<Order> orders, Map<String, Map<Long, Integer>> snapshot, List<Center> centers,
                                Map<String, List<String>> outsideCentersByRegion) {
        Map<String, Map<Long, Integer>> stock = copy(snapshot);
        Map<String, List<String>> homeCentersByRegion = homeCenters(centers);
        Map<String, Map<Long, Integer>> remaining = new LinkedHashMap<>();
        orders.forEach(order -> remaining.put(order.orderNo(), order.needs()));
        Map<ResultKey, Integer> results = new LinkedHashMap<>();

        for (Order order : orders) {
            Map<Long, Integer> needs = remaining.get(order.orderNo());
            homeCentersByRegion.getOrDefault(order.regionCode(), List.of()).stream()
                    .filter(centerId -> canShipAll(stock.get(centerId), needs))
                    .max(Comparator.<String>comparingLong(centerId -> stockFor(stock.get(centerId), needs))
                            .thenComparing(Comparator.<String>reverseOrder()))
                    .ifPresent(centerId -> take(order, centerId, stock, remaining, results));
        }
        for (Order order : orders) {
            homeCentersByRegion.getOrDefault(order.regionCode(), List.of())
                    .forEach(centerId -> take(order, centerId, stock, remaining, results));
        }
        for (Order order : orders) {
            outsideCentersByRegion.getOrDefault(order.regionCode(), List.of())
                    .forEach(centerId -> take(order, centerId, stock, remaining, results));
        }
        return new Plan(toResults(results), decide(orders, remaining, stock));
    }

    // 전량 나갈 수 있는 센터가 여럿이면 이 주문의 품목 재고가 가장 많이 남은 센터를 고른다. 같으면 센터 번호가 앞선 쪽이다.
    // 첫 센터만 고르면 재고가 넉넉한 날 권역의 주문이 모두 한 센터로 몰려 출고 지시 시간이 그 센터에 묶인다
    // (docs/experiments/04-출고-배치.md)
    private static long stockFor(Map<Long, Integer> centerStock, Map<Long, Integer> needs) {
        return needs.keySet().stream().mapToLong(productId -> centerStock.getOrDefault(productId, 0)).sum();
    }

    private static boolean canShipAll(Map<Long, Integer> centerStock, Map<Long, Integer> needs) {
        if (centerStock == null) {
            return false;
        }
        return needs.entrySet().stream().allMatch(need -> centerStock.getOrDefault(need.getKey(), 0) >= need.getValue());
    }

    private static void take(Order order, String centerId, Map<String, Map<Long, Integer>> stock,
                             Map<String, Map<Long, Integer>> remaining, Map<ResultKey, Integer> results) {
        Map<Long, Integer> centerStock = stock.get(centerId);
        if (centerStock == null) {
            return;
        }
        for (Map.Entry<Long, Integer> need : remaining.get(order.orderNo()).entrySet()) {
            int taken = Math.min(need.getValue(), centerStock.getOrDefault(need.getKey(), 0));
            if (taken == 0) {
                continue;
            }
            centerStock.merge(need.getKey(), -taken, Integer::sum);
            need.setValue(need.getValue() - taken);
            results.merge(new ResultKey(order.orderNo(), need.getKey(), centerId), taken, Integer::sum);
        }
    }

    // 다 돈 뒤에도 어느 센터에 재고가 남아 있으면 그 재고는 이 권역에 새벽에 닿지 못하는 것이다
    private static List<Decision> decide(List<Order> orders, Map<String, Map<Long, Integer>> remaining,
                                         Map<String, Map<Long, Integer>> stock) {
        List<Decision> decisions = new ArrayList<>();
        for (Order order : orders) {
            remaining.get(order.orderNo()).forEach((productId, quantity) -> {
                if (quantity > 0) {
                    boolean stockLeftSomewhere = stock.values().stream().anyMatch(centerStock -> centerStock.getOrDefault(productId, 0) > 0);
                    decisions.add(new Decision(order.orderNo(), productId, quantity, stockLeftSomewhere ? REGION_MISMATCH : STOCKTAKE_GAP));
                }
            });
        }
        return decisions;
    }

    private static Map<String, List<String>> homeCenters(List<Center> centers) {
        Map<String, List<String>> byRegion = new HashMap<>();
        centers.stream()
                .sorted(Comparator.comparing(Center::centerId))
                .forEach(center -> byRegion.computeIfAbsent(center.regionCode(), region -> new ArrayList<>()).add(center.centerId()));
        return byRegion;
    }

    private static Map<String, Map<Long, Integer>> copy(Map<String, Map<Long, Integer>> snapshot) {
        Map<String, Map<Long, Integer>> copy = new HashMap<>();
        snapshot.forEach((centerId, products) -> copy.put(centerId, new HashMap<>(products)));
        return copy;
    }

    private static List<Result> toResults(Map<ResultKey, Integer> results) {
        return results.entrySet().stream()
                .map(entry -> new Result(entry.getKey().orderNo(), entry.getKey().productId(), entry.getKey().centerId(), entry.getValue()))
                .toList();
    }

    public record Order(String orderNo, String regionCode, List<Line> lines) {

        public Order {
            lines = List.copyOf(lines);
        }

        Map<Long, Integer> needs() {
            Map<Long, Integer> needs = new LinkedHashMap<>();
            lines.stream().sorted(Comparator.comparingLong(Line::productId))
                    .forEach(line -> needs.merge(line.productId(), line.quantity(), Integer::sum));
            return needs;
        }
    }

    public record Line(long productId, int quantity) {
    }

    public record Center(String centerId, String regionCode) {
    }

    public record Result(String orderNo, long productId, String centerId, int quantity) {
    }

    public record Decision(String orderNo, long productId, int quantity, String reason) {
    }

    public record Plan(List<Result> results, List<Decision> decisions) {
    }

    private record ResultKey(String orderNo, long productId, String centerId) {
    }
}
