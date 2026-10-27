package io.github.bamdule.dawn.order.client;

import java.time.LocalDateTime;

public record ReserveResult(boolean reserved, LocalDateTime expiresAt, Long soldOutProductId) {

    public static ReserveResult reserved(LocalDateTime expiresAt) {
        return new ReserveResult(true, expiresAt, null);
    }

    public static ReserveResult soldOut(long productId) {
        return new ReserveResult(false, null, productId);
    }
}
