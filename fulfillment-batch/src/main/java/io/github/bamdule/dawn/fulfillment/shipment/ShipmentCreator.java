package io.github.bamdule.dawn.fulfillment.shipment;

import io.github.bamdule.dawn.fulfillment.run.CutoffRunRepository;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import io.github.bamdule.dawn.fulfillment.run.StepPreconditionException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 센터 하나의 할당 결과를 (센터, 주문) 단위로 묶어 출고 지시를 만든다. 파티션마다 자기 센터의 실물 재고 행만 차감한다.
// 출고 지시, 실물 재고 차감, 대기 수량의 출고 이동을 한 트랜잭션에 둔다 (docs/design/05 "4. 출고 지시")
@Service
public class ShipmentCreator {

    private static final Logger log = LoggerFactory.getLogger(ShipmentCreator.class);
    private static final int MAX_DEADLOCK_RETRIES = 3;

    private final CutoffRunRepository cutoffRunRepository;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final int chunkSize;

    public ShipmentCreator(CutoffRunRepository cutoffRunRepository, JdbcClient jdbcClient, TransactionTemplate transactionTemplate,
                           @Value("${dawn.fulfillment.shipment.chunk-size:500}") int chunkSize) {
        this.cutoffRunRepository = cutoffRunRepository;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.chunkSize = chunkSize;
    }

    // 주문번호 순으로 읽는다. 정렬이 없으면 재시작 때 건너뛰거나 겹치고, 두 파티션이 같은 품목 행을 다른 순서로 잠가 교착한다
    public void createForCenter(LocalDateTime cutoffAt, String centerId) {
        CutoffRunStatus status = cutoffRunRepository.status(cutoffAt);
        if (status != CutoffRunStatus.UNFILLABLE_PUBLISHED && status != CutoffRunStatus.SHIPMENT_CREATED) {
            throw new StepPreconditionException("판정 발행 전에는 출고 지시를 만들 수 없습니다. (cutoffAt=" + cutoffAt + ")");
        }
        String lastOrderNo = "";
        while (true) {
            Map<String, List<ShipmentLine>> drafts = readChunk(cutoffAt, centerId, lastOrderNo);
            if (drafts.isEmpty()) {
                return;
            }
            commitWithDeadlockRetry(cutoffAt, centerId, drafts);
            lastOrderNo = new ArrayList<>(drafts.keySet()).getLast();
        }
    }

    private Map<String, List<ShipmentLine>> readChunk(LocalDateTime cutoffAt, String centerId, String afterOrderNo) {
        List<String> orderNos = jdbcClient.sql("""
                SELECT DISTINCT order_no FROM allocation_result
                 WHERE cutoff_at = :cutoffAt AND center_id = :centerId AND order_no > :afterOrderNo
                 ORDER BY order_no LIMIT :chunkSize
                """)
                .param("cutoffAt", cutoffAt)
                .param("centerId", centerId)
                .param("afterOrderNo", afterOrderNo)
                .param("chunkSize", chunkSize)
                .query(String.class)
                .list();
        Map<String, List<ShipmentLine>> drafts = new LinkedHashMap<>();
        if (orderNos.isEmpty()) {
            return drafts;
        }
        jdbcClient.sql("""
                SELECT order_no, product_id, quantity FROM allocation_result
                 WHERE cutoff_at = :cutoffAt AND center_id = :centerId AND order_no IN (:orderNos)
                 ORDER BY order_no, product_id
                """)
                .param("cutoffAt", cutoffAt)
                .param("centerId", centerId)
                .param("orderNos", orderNos)
                .query(rs -> {
                    drafts.computeIfAbsent(rs.getString("order_no"), key -> new ArrayList<>())
                            .add(new ShipmentLine(rs.getLong("product_id"), rs.getInt("quantity")));
                });
        return drafts;
    }

    // 교착으로 진 트랜잭션은 MySQL 이 통째로 되돌리므로 청크를 처음부터 다시 한다. 이미 만든 출고 지시는 유니크로 건너뛴다
    private void commitWithDeadlockRetry(LocalDateTime cutoffAt, String centerId, Map<String, List<ShipmentLine>> drafts) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        drafts.forEach((orderNo, lines) -> createOne(cutoffAt, centerId, orderNo, lines)));
                return;
            } catch (PessimisticLockingFailureException lockingFailure) {
                if (attempt == MAX_DEADLOCK_RETRIES) {
                    throw lockingFailure;
                }
                log.warn("출고 지시 청크 교착 재시도 (cutoffAt={}, centerId={}, attempt={})", cutoffAt, centerId, attempt);
            }
        }
    }

    private void createOne(LocalDateTime cutoffAt, String centerId, String orderNo, List<ShipmentLine> lines) {
        if (alreadyHandled(cutoffAt, centerId, orderNo)) {
            return;
        }
        List<Long> productIds = lines.stream().map(ShipmentLine::productId).toList();
        Map<Long, Integer> waiting = lockQuantities("""
                SELECT product_id, waiting_qty AS quantity FROM shipment_target_line
                 WHERE order_no = :key AND product_id IN (:productIds) ORDER BY product_id FOR UPDATE
                """, orderNo, productIds);
        Map<Long, Integer> stock = lockQuantities("""
                SELECT product_id, quantity FROM center_stock
                 WHERE center_id = :key AND product_id IN (:productIds) ORDER BY product_id FOR UPDATE
                """, centerId, productIds);
        String invalidReason = invalidReason(orderNo, lines, waiting, stock);
        if (invalidReason == null) {
            ship(cutoffAt, centerId, orderNo, lines);
        } else {
            recordException(cutoffAt, centerId, orderNo, lines, waiting, invalidReason);
        }
    }

    private boolean alreadyHandled(LocalDateTime cutoffAt, String centerId, String orderNo) {
        return jdbcClient.sql("""
                SELECT (SELECT COUNT(*) FROM shipment_order WHERE cutoff_at = :cutoffAt AND order_no = :orderNo AND center_id = :centerId)
                     + (SELECT COUNT(*) FROM shipment_exception WHERE cutoff_at = :cutoffAt AND order_no = :orderNo AND center_id = :centerId)
                """)
                .param("cutoffAt", cutoffAt)
                .param("orderNo", orderNo)
                .param("centerId", centerId)
                .query(Integer.class)
                .single() > 0;
    }

    private Map<Long, Integer> lockQuantities(String sql, String key, List<Long> productIds) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        jdbcClient.sql(sql)
                .param("key", key)
                .param("productIds", productIds)
                .query(rs -> {
                    quantities.put(rs.getLong("product_id"), rs.getInt("quantity"));
                });
        return quantities;
    }

    private String invalidReason(String orderNo, List<ShipmentLine> lines, Map<Long, Integer> waiting, Map<Long, Integer> stock) {
        String address = jdbcClient.sql("SELECT address FROM shipment_target WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(String.class).optional().orElse(null);
        if (address == null || address.isBlank()) {
            return "ADDRESS_INVALID";
        }
        for (ShipmentLine line : lines) {
            if (waiting.getOrDefault(line.productId(), 0) < line.quantity()) {
                return "WAITING_SHORT";
            }
            if (stock.getOrDefault(line.productId(), 0) < line.quantity()) {
                return "STOCK_SHORT";
            }
        }
        return null;
    }

    private void ship(LocalDateTime cutoffAt, String centerId, String orderNo, List<ShipmentLine> lines) {
        long shipmentOrderId = insertHeader("""
                INSERT INTO shipment_order (cutoff_at, order_no, center_id, status, created_at)
                VALUES (:cutoffAt, :orderNo, :centerId, 'CREATED', NOW(3))
                """, cutoffAt, orderNo, centerId, null);
        for (ShipmentLine line : lines) {
            insertLine("INSERT INTO shipment_order_line (shipment_order_id, product_id, quantity) VALUES (:id, :productId, :quantity)",
                    shipmentOrderId, line.productId(), line.quantity());
            jdbcClient.sql("""
                    UPDATE center_stock SET quantity = quantity - :quantity, updated_at = NOW(3)
                     WHERE center_id = :centerId AND product_id = :productId AND quantity >= :quantity
                    """)
                    .param("centerId", centerId).param("productId", line.productId()).param("quantity", line.quantity())
                    .update();
            moveWaiting(orderNo, line.productId(), line.quantity(), "shipped_qty");
        }
    }

    // 예외 수량은 실제로 옮긴 수량으로 남긴다. 사람이 이월하거나 취소할 때 이 수량만큼 되돌린다
    private void recordException(LocalDateTime cutoffAt, String centerId, String orderNo, List<ShipmentLine> lines,
                                 Map<Long, Integer> waiting, String reason) {
        long exceptionId = insertHeader("""
                INSERT INTO shipment_exception (cutoff_at, order_no, center_id, reason, status, created_at)
                VALUES (:cutoffAt, :orderNo, :centerId, :reason, 'OPEN', NOW(3))
                """, cutoffAt, orderNo, centerId, reason);
        for (ShipmentLine line : lines) {
            int moved = Math.min(line.quantity(), waiting.getOrDefault(line.productId(), 0));
            if (moved == 0) {
                continue;
            }
            insertLine("INSERT INTO shipment_exception_line (shipment_exception_id, product_id, quantity) VALUES (:id, :productId, :quantity)",
                    exceptionId, line.productId(), moved);
            moveWaiting(orderNo, line.productId(), moved, "exception_qty");
        }
        log.error("출고 예외 (cutoffAt={}, centerId={}, orderNo={}, reason={}, exceptionNo={})",
                cutoffAt, centerId, orderNo, reason, exceptionId);
    }

    private void moveWaiting(String orderNo, long productId, int quantity, String targetColumn) {
        jdbcClient.sql("""
                UPDATE shipment_target_line
                   SET waiting_qty = waiting_qty - :quantity, %s = %s + :quantity
                 WHERE order_no = :orderNo AND product_id = :productId AND waiting_qty >= :quantity
                """.formatted(targetColumn, targetColumn))
                .param("orderNo", orderNo).param("productId", productId).param("quantity", quantity)
                .update();
    }

    private long insertHeader(String sql, LocalDateTime cutoffAt, String orderNo, String centerId, String reason) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        var statement = jdbcClient.sql(sql).param("cutoffAt", cutoffAt).param("orderNo", orderNo).param("centerId", centerId);
        if (reason != null) {
            statement = statement.param("reason", reason);
        }
        statement.update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }

    private void insertLine(String sql, long id, long productId, int quantity) {
        jdbcClient.sql(sql).param("id", id).param("productId", productId).param("quantity", quantity).update();
    }

    private record ShipmentLine(long productId, int quantity) {
    }
}
