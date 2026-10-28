package io.github.bamdule.dawn.fulfillment.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Center;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Line;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Order;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Plan;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Result;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AllocatorTest {

    private static final List<Center> CENTERS = List.of(new Center("SEOUL-A", "SEOUL"), new Center("SEOUL-B", "SEOUL"));

    @Test
    void 전량_나갈_수_있는_센터가_여럿이면_재고가_많이_남은_센터를_골라_고르게_나뉜다() {
        Map<String, Map<Long, Integer>> snapshot = Map.of(
                "SEOUL-A", new HashMap<>(Map.of(1L, 1_000)),
                "SEOUL-B", new HashMap<>(Map.of(1L, 1_000)));
        List<Order> orders = IntStream.range(0, 100)
                .mapToObj(index -> new Order("O" + index, "SEOUL", List.of(new Line(1L, 1))))
                .toList();

        Plan plan = Allocator.allocate(orders, snapshot, CENTERS, Map.of());

        Map<String, Integer> byCenter = plan.results().stream()
                .collect(Collectors.groupingBy(Result::centerId, Collectors.summingInt(Result::quantity)));
        assertThat(byCenter).isEqualTo(Map.of("SEOUL-A", 50, "SEOUL-B", 50));
    }

    @Test
    void 같은_입력이면_결과가_늘_같다() {
        Map<String, Map<Long, Integer>> snapshot = Map.of(
                "SEOUL-A", new HashMap<>(Map.of(1L, 3, 2L, 1)),
                "SEOUL-B", new HashMap<>(Map.of(1L, 2, 2L, 2)));
        List<Order> orders = List.of(
                new Order("O1", "SEOUL", List.of(new Line(1L, 2), new Line(2L, 1))),
                new Order("O2", "SEOUL", List.of(new Line(1L, 2))),
                new Order("O3", "SEOUL", List.of(new Line(1L, 1), new Line(2L, 2))));

        Plan first = Allocator.allocate(orders, snapshot, CENTERS, Map.of());
        Plan second = Allocator.allocate(orders, snapshot, CENTERS, Map.of());

        assertThat(second).isEqualTo(first);
    }
}
