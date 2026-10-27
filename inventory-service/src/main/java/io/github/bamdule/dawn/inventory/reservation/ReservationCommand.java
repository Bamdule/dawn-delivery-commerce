package io.github.bamdule.dawn.inventory.reservation;

import io.github.bamdule.dawn.inventory.stock.StockLine;
import java.util.List;
import java.util.Objects;

public record ReservationCommand(String orderNo, String regionCode, List<StockLine> lines) {

    public ReservationCommand {
        Objects.requireNonNull(orderNo, "orderNo");
        Objects.requireNonNull(regionCode, "regionCode");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("예약할 품목이 없습니다.");
        }
    }
}
