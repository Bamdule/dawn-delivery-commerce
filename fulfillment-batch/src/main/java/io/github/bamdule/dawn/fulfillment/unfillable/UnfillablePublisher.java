package io.github.bamdule.dawn.fulfillment.unfillable;

import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.fulfillment.event.FulfillmentEventPublisher;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunRepository;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import io.github.bamdule.dawn.fulfillment.run.StepPreconditionException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 할당이 끝난 뒤 주문마다 한 트랜잭션에서 대기 수량을 못 채움으로 옮기고 UNFILLABLE 하나를 쓴다 (docs/design/05 "3. 판정 발행")
@Service
public class UnfillablePublisher {

    private final CutoffRunRepository cutoffRunRepository;
    private final FulfillmentEventPublisher fulfillmentEventPublisher;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public UnfillablePublisher(CutoffRunRepository cutoffRunRepository, FulfillmentEventPublisher fulfillmentEventPublisher,
                               JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.cutoffRunRepository = cutoffRunRepository;
        this.fulfillmentEventPublisher = fulfillmentEventPublisher;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    public void publish(LocalDateTime cutoffAt) {
        CutoffRunStatus status = cutoffRunRepository.status(cutoffAt);
        if (status.reached(CutoffRunStatus.UNFILLABLE_PUBLISHED)) {
            return;
        }
        if (status != CutoffRunStatus.ALLOCATED) {
            throw new StepPreconditionException("할당이 끝나기 전에는 판정을 낼 수 없습니다. (cutoffAt=" + cutoffAt + ")");
        }
        List<String> orderNos = jdbcClient.sql("SELECT DISTINCT order_no FROM unfillable_decision WHERE cutoff_at = :cutoffAt ORDER BY order_no")
                .param("cutoffAt", cutoffAt)
                .query(String.class)
                .list();
        for (String orderNo : orderNos) {
            try {
                transactionTemplate.executeWithoutResult(transactionStatus -> publishOne(cutoffAt, orderNo));
            } catch (DuplicateKeyException alreadyPublished) {
                // 재시작으로 다시 읽은 주문이다. 이벤트 아이디가 같아 트랜잭션 전체가 되돌아가 수량을 두 번 옮기지 않는다
            }
        }
        cutoffRunRepository.transition(cutoffAt, CutoffRunStatus.ALLOCATED, CutoffRunStatus.UNFILLABLE_PUBLISHED);
    }

    private void publishOne(LocalDateTime cutoffAt, String orderNo) {
        List<OrderLine> lines = jdbcClient.sql("""
                SELECT product_id, quantity FROM unfillable_decision WHERE cutoff_at = :cutoffAt AND order_no = :orderNo ORDER BY product_id
                """)
                .param("cutoffAt", cutoffAt)
                .param("orderNo", orderNo)
                .query(OrderLine.class)
                .list();
        fulfillmentEventPublisher.unfillable(orderNo, cutoffAt, lines);
        for (OrderLine line : lines) {
            int moved = jdbcClient.sql("""
                    UPDATE shipment_target_line
                       SET waiting_qty = waiting_qty - :quantity, unfilled_qty = unfilled_qty + :quantity
                     WHERE order_no = :orderNo AND product_id = :productId AND waiting_qty >= :quantity
                    """)
                    .param("orderNo", orderNo)
                    .param("productId", line.productId())
                    .param("quantity", line.quantity())
                    .update();
            if (moved == 0) {
                throw new IllegalStateException("판정 수량보다 대기 수량이 적습니다. (orderNo=" + orderNo + ", productId=" + line.productId() + ")");
            }
        }
    }
}
