package io.github.bamdule.dawn.inventory.reservation;

import io.github.bamdule.dawn.inventory.stock.OutOfStockException;
import io.github.bamdule.dawn.inventory.stock.StockDeduction;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Reservations {

    private final ReservationRepository reservationRepository;
    private final StockDeduction stockDeduction;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public Reservations(ReservationRepository reservationRepository, StockDeduction stockDeduction,
                        JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.reservationRepository = reservationRepository;
        this.stockDeduction = stockDeduction;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    // 같은 주문의 예약이 이미 있으면 새로 잡지 않고 그 만료 시각을 돌려준다. 주문 서비스의 재시도가 한 벌만 만든다
    public LocalDateTime reserve(ReservationCommand reservationCommand) {
        List<ReservationRow> existing = reservationRepository.findByOrderNo(reservationCommand.orderNo());
        if (!existing.isEmpty()) {
            return existing.getFirst().expiresAt();
        }
        try {
            return transactionTemplate.execute(status -> reserveNew(reservationCommand));
        } catch (DuplicateKeyException duplicateKeyException) {
            // 같은 요청이 동시에 들어와 다른 쪽이 먼저 커밋했다. 이쪽 차감은 함께 되돌아갔다
            return reservationRepository.findByOrderNo(reservationCommand.orderNo()).getFirst().expiresAt();
        }
    }

    private LocalDateTime reserveNew(ReservationCommand reservationCommand) {
        for (StockLine stockLine : reservationCommand.lines()) {
            if (isBlocked(stockLine.productId(), reservationCommand.regionCode())) {
                throw new OutOfStockException(stockLine.productId());
            }
        }
        stockDeduction.deduct(reservationCommand.lines());
        LocalDateTime expiresAt = expiresAtFor(reservationCommand.lines());
        for (StockLine stockLine : reservationCommand.lines()) {
            reservationRepository.insertHeld(reservationCommand.orderNo(), stockLine.productId(),
                    stockLine.quantity(), expiresAt);
        }
        return expiresAt;
    }

    // 한 주문의 예약은 품목 중 가장 짧은 유지 시간으로 만료 시각이 하나다 (docs/design/02)
    private LocalDateTime expiresAtFor(List<StockLine> stockLines) {
        List<Long> productIds = stockLines.stream().map(StockLine::productId).toList();
        return jdbcClient.sql("""
                SELECT NOW(3) + INTERVAL MIN(hold_minutes) MINUTE FROM stock WHERE product_id IN (:productIds)
                """)
                .param("productIds", productIds)
                .query(LocalDateTime.class)
                .single();
    }

    private boolean isBlocked(long productId, String regionCode) {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_region_block WHERE product_id = :productId AND region_code = :regionCode")
                .param("productId", productId)
                .param("regionCode", regionCode)
                .query(Integer.class)
                .single() > 0;
    }

    public MarkPayingResult markPaying(String orderNo, int paymentRound) {
        return transactionTemplate.execute(status -> {
            List<ReservationRow> rows = reservationRepository.findByOrderNo(orderNo);
            if (rows.isEmpty()) {
                throw new ReservationNotFoundException(orderNo);
            }
            int changed = reservationRepository.markPaying(orderNo, paymentRound);
            if (changed == rows.size()) {
                return MarkPayingResult.marked(paymentRound, rows.getFirst().expiresAt());
            }
            if (changed > 0) {
                // 만료 경계에서 만료 배치가 한 행을 먼저 풀었다. 바뀐 행만 되돌리고 결제하지 않게 한다
                status.setRollbackOnly();
            }
            ReservationRow current = rows.getFirst();
            ReservationRow reread = reservationRepository.findByOrderNo(orderNo).getFirst();
            return MarkPayingResult.notMarked(changed > 0 ? ReservationStatus.RELEASED : reread.status(),
                    reread.paymentRound(), current.expiresAt());
        });
    }

    public boolean revertPaying(String orderNo, int paymentRound) {
        return reservationRepository.revertPaying(orderNo, paymentRound) > 0;
    }
}
