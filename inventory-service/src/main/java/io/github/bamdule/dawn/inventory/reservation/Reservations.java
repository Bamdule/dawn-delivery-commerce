package io.github.bamdule.dawn.inventory.reservation;

import io.github.bamdule.dawn.inventory.stock.OutOfStockException;
import io.github.bamdule.dawn.inventory.stock.RandomSlotMissException;
import io.github.bamdule.dawn.inventory.stock.StockDeduction;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import io.github.bamdule.dawn.inventory.stock.SlotAllocation;
import java.util.Map;
import java.util.Optional;
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
            return reserveWithOrderedRetry(reservationCommand);
        } catch (DuplicateKeyException duplicateKeyException) {
            // 같은 요청이 동시에 들어와 다른 쪽이 먼저 커밋했다. 이쪽 차감은 함께 되돌아갔다
            return reservationRepository.findByOrderNo(reservationCommand.orderNo()).getFirst().expiresAt();
        }
    }

    // 무작위 슬롯이 모자라면 트랜잭션을 되돌려 잠금을 모두 풀고, 슬롯 번호 순으로만 잠그는 방식으로 다시 한다
    private LocalDateTime reserveWithOrderedRetry(ReservationCommand reservationCommand) {
        try {
            return transactionTemplate.execute(status -> reserveNew(reservationCommand, false));
        } catch (RandomSlotMissException randomSlotMissException) {
            return transactionTemplate.execute(status -> reserveNew(reservationCommand, true));
        }
    }

    private LocalDateTime reserveNew(ReservationCommand reservationCommand, boolean orderedSlotsOnly) {
        for (StockLine stockLine : reservationCommand.lines()) {
            if (isBlocked(stockLine.productId(), reservationCommand.regionCode())) {
                throw new OutOfStockException(stockLine.productId());
            }
        }
        // 예약 행을 먼저 넣고 차감을 맨 끝에 둔다. 몰리는 재고 행의 잠금은 차감부터 커밋까지 쥐므로,
        // 차감 뒤에 조회와 삽입을 하면 그만큼 잠금이 길어진다. 순서를 바꾸자 단일 상품 예약이 초당 약 500건에서 늘었다
        // (docs/experiments/01-재고-동시성.md). 슬롯 내역만은 차감 결과라 뒤에 남는다. 슬롯은 여럿이라 덜 몰린다
        LocalDateTime expiresAt = expiresAtFor(reservationCommand.lines());
        Map<Long, String> reservationIds = new HashMap<>();
        for (StockLine stockLine : reservationCommand.lines()) {
            reservationIds.put(stockLine.productId(), reservationRepository.insertHeld(reservationCommand.orderNo(),
                    stockLine.productId(), stockLine.quantity(), expiresAt));
        }
        Map<Long, List<SlotAllocation>> allocations = stockDeduction.deduct(reservationCommand.lines(), orderedSlotsOnly);
        allocations.forEach((productId, slotAllocations) -> slotAllocations
                .forEach(slotAllocation -> reservationRepository.insertSlotAllocation(reservationIds.get(productId), slotAllocation)));
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
            ReservationState reread = reservationRepository.findState(orderNo).orElseThrow();
            if (changed > 0) {
                // 만료 경계에서 만료 배치가 한 행을 먼저 풀었다. 바뀐 행만 되돌리고 결제하지 않게 한다
                status.setRollbackOnly();
                return MarkPayingResult.notMarked(new ReservationState(ReservationStatus.RELEASED, reread.paymentRound(),
                        reread.expiresAt(), true));
            }
            return MarkPayingResult.notMarked(reread);
        });
    }

    public Optional<ReservationState> currentState(String orderNo) {
        return reservationRepository.findState(orderNo);
    }

    public boolean revertPaying(String orderNo, int paymentRound) {
        return reservationRepository.revertPaying(orderNo, paymentRound) > 0;
    }
}
