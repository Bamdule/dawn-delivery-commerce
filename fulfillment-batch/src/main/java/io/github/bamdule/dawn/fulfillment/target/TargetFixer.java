package io.github.bamdule.dawn.fulfillment.target;

import io.github.bamdule.dawn.fulfillment.run.CutoffRunRepository;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import io.github.bamdule.dawn.fulfillment.run.StepPreconditionException;
import io.github.bamdule.dawn.fulfillment.target.OrderClient.OrderDetail;
import io.github.bamdule.dawn.fulfillment.target.OrderClient.PaidPage;
import io.github.bamdule.dawn.fulfillment.target.ShipmentTargetRepository.NewTarget;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 배치 첫 스텝. 이벤트로 쌓아 둔 출고 대상을 주문 서비스의 결제 완료 목록과 대조해 이번 회차의 입력을 고정한다 (docs/design/05 "1. 대상 확정")
@Service
public class TargetFixer {

    private static final Logger log = LoggerFactory.getLogger(TargetFixer.class);
    private static final int PAGE_SIZE = 1_000;
    private static final int IN_CLAUSE_SIZE = 1_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long CUTOFF_INTERVAL_DAYS = 1;

    private final OrderClient orderClient;
    private final ShipmentTargetRepository shipmentTargetRepository;
    private final CutoffRunRepository cutoffRunRepository;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final Duration retryDelay;

    public TargetFixer(OrderClient orderClient, ShipmentTargetRepository shipmentTargetRepository,
                       CutoffRunRepository cutoffRunRepository, JdbcClient jdbcClient, TransactionTemplate transactionTemplate,
                       @Value("${dawn.fulfillment.target-fix.retry-delay:1m}") Duration retryDelay) {
        this.orderClient = orderClient;
        this.shipmentTargetRepository = shipmentTargetRepository;
        this.cutoffRunRepository = cutoffRunRepository;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.retryDelay = retryDelay;
    }

    public void fix(LocalDateTime cutoffAt) {
        requirePreviousFinished(cutoffAt);
        cutoffRunRepository.startIfAbsent(cutoffAt);
        if (cutoffRunRepository.status(cutoffAt).reached(CutoffRunStatus.TARGET_FIXED)) {
            return;
        }
        for (int attempt = 1; ; attempt++) {
            Set<String> listed = fetchPaidOrders(cutoffAt);
            fillMissing(listed);
            assignListed(cutoffAt, listed);
            assignCarriedOver(cutoffAt);
            cancelUnlisted(cutoffAt, listed);
            Comparison comparison = compare(cutoffAt, listed);
            if (comparison.matched()) {
                fixInput(cutoffAt, listed.size());
                return;
            }
            if (attempt == MAX_ATTEMPTS) {
                recordDiff(cutoffAt, comparison);
                log.error("대상 확정 불일치 (cutoffAt={}, missing={}, extra={})",
                        cutoffAt, comparison.missing().size(), comparison.extra().size());
                throw new TargetFixMismatchException(listed.size(), comparison.fixedCount());
            }
            sleep();
        }
    }

    // 앞 회차가 아직 출고 중이면 그 회차의 대기 수량을 이월로 오해해 가져갈 수 있다
    private void requirePreviousFinished(LocalDateTime cutoffAt) {
        Optional<CutoffRunStatus> previous = cutoffRunRepository.findStatus(cutoffAt.minusDays(CUTOFF_INTERVAL_DAYS));
        if (previous.isPresent() && !previous.get().finished()) {
            log.error("앞 회차 미완료로 대상 확정 보류 (cutoffAt={}, previousStatus={})", cutoffAt, previous.get());
            throw new StepPreconditionException("앞 회차가 끝나지 않았습니다. (cutoffAt=" + cutoffAt + ")");
        }
    }

    private Set<String> fetchPaidOrders(LocalDateTime cutoffAt) {
        Set<String> orderNos = new LinkedHashSet<>();
        PaidPage page = orderClient.paidOrders(cutoffAt, null, null, PAGE_SIZE);
        orderNos.addAll(page.orderNos());
        while (!page.last()) {
            page = orderClient.paidOrders(cutoffAt, page.nextCursorPaidAt(), page.nextCursorId(), PAGE_SIZE);
            orderNos.addAll(page.orderNos());
        }
        return orderNos;
    }

    // 이벤트가 늦거나 처리 실패 토픽으로 빠진 주문은 상세를 받아 넣는다
    private void fillMissing(Set<String> listed) {
        for (List<String> chunk : chunks(listed)) {
            Set<String> existing = new HashSet<>(shipmentTargetRepository.findExisting(chunk));
            for (String orderNo : chunk) {
                if (existing.contains(orderNo)) {
                    continue;
                }
                Optional<OrderDetail> detail = orderClient.detail(orderNo);
                if (detail.isEmpty()) {
                    log.warn("결제 완료 목록의 주문 상세 없음 (orderNo={})", orderNo);
                    continue;
                }
                transactionTemplate.executeWithoutResult(status -> insert(detail.get()));
            }
        }
    }

    private void insert(OrderDetail orderDetail) {
        if (!"PAID".equals(orderDetail.status())) {
            shipmentTargetRepository.insertCancelledMark(orderDetail.orderNo());
            return;
        }
        shipmentTargetRepository.insertWaiting(new NewTarget(orderDetail.orderNo(), orderDetail.regionCode(),
                orderDetail.recipientName(), orderDetail.address(), orderDetail.paidAt(),
                orderDetail.items().stream()
                        .map(item -> new NewTarget.Line(item.productId(), item.quantity(), item.cancelledQuantity()))
                        .toList()));
    }

    // 배정 회차가 빈 품목은 목록에 오른 주문만 가져간다. 마감 뒤에 결제해 먼저 등록된 주문은 다음 회차를 기다린다
    private void assignListed(LocalDateTime cutoffAt, Set<String> listed) {
        for (List<String> chunk : chunks(listed)) {
            jdbcClient.sql("""
                    UPDATE shipment_target_line l JOIN shipment_target t ON t.order_no = l.order_no
                       SET l.assigned_cutoff_at = :cutoffAt
                     WHERE l.order_no IN (:orderNos) AND l.assigned_cutoff_at IS NULL AND l.waiting_qty > 0
                       AND t.status = 'WAITING'
                    """)
                    .param("cutoffAt", cutoffAt)
                    .param("orderNos", chunk)
                    .update();
        }
    }

    // 이월은 배정 회차를 그대로 둬서, 지난 회차 값이 남은 대기 수량이 곧 이월된 수량이다
    private void assignCarriedOver(LocalDateTime cutoffAt) {
        jdbcClient.sql("""
                UPDATE shipment_target_line l JOIN shipment_target t ON t.order_no = l.order_no
                   SET l.assigned_cutoff_at = :cutoffAt
                 WHERE l.assigned_cutoff_at < :cutoffAt AND l.waiting_qty > 0 AND t.status = 'WAITING'
                """)
                .param("cutoffAt", cutoffAt)
                .update();
    }

    // 이번 회차 범위에 결제됐는데 목록에 없으면 마감 전에 취소된 주문이다. 취소 이벤트가 늦어도 여기서 대기 수량을 취소로 옮긴다
    private void cancelUnlisted(LocalDateTime cutoffAt, Set<String> listed) {
        List<String> inRange = jdbcClient.sql("""
                SELECT order_no FROM shipment_target
                 WHERE status = 'WAITING' AND paid_at > :previousCutoff AND paid_at <= :cutoffAt
                """)
                .param("previousCutoff", cutoffAt.minusDays(CUTOFF_INTERVAL_DAYS))
                .param("cutoffAt", cutoffAt)
                .query(String.class)
                .list();
        for (String orderNo : inRange) {
            if (!listed.contains(orderNo)) {
                transactionTemplate.executeWithoutResult(status -> {
                    shipmentTargetRepository.cancelAllWaiting(orderNo);
                    shipmentTargetRepository.markCancelled(orderNo);
                });
            }
        }
    }

    // 이월만 있는 주문은 앞 회차에 결제된 주문이라 paid_at 범위로 뺀다
    private Comparison compare(LocalDateTime cutoffAt, Set<String> listed) {
        Set<String> fixed = new HashSet<>(jdbcClient.sql("""
                SELECT DISTINCT l.order_no FROM shipment_target_line l JOIN shipment_target t ON t.order_no = l.order_no
                 WHERE l.assigned_cutoff_at = :cutoffAt AND l.waiting_qty > 0 AND t.status = 'WAITING'
                   AND t.paid_at > :previousCutoff AND t.paid_at <= :cutoffAt
                """)
                .param("cutoffAt", cutoffAt)
                .param("previousCutoff", cutoffAt.minusDays(CUTOFF_INTERVAL_DAYS))
                .query(String.class)
                .list());
        List<String> missing = listed.stream().filter(orderNo -> !fixed.contains(orderNo)).toList();
        List<String> extra = fixed.stream().filter(orderNo -> !listed.contains(orderNo)).toList();
        return new Comparison(missing, extra, fixed.size());
    }

    // 할당과 그 재시작은 이 표만 읽는다. 배치 중 이월이 품목 행을 바꿔도 이번 회차의 입력은 그대로다
    private void fixInput(LocalDateTime cutoffAt, int targetCount) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("DELETE FROM allocation_input WHERE cutoff_at = :cutoffAt").param("cutoffAt", cutoffAt).update();
            jdbcClient.sql("""
                    INSERT INTO allocation_input (cutoff_at, order_no, product_id, quantity, region_code, paid_at)
                    SELECT :cutoffAt, l.order_no, l.product_id, l.waiting_qty, t.region_code, t.paid_at
                      FROM shipment_target_line l JOIN shipment_target t ON t.order_no = l.order_no
                     WHERE l.assigned_cutoff_at = :cutoffAt AND l.waiting_qty > 0 AND t.status = 'WAITING'
                    """)
                    .param("cutoffAt", cutoffAt)
                    .update();
            int inputQuantity = jdbcClient.sql("SELECT COALESCE(SUM(quantity), 0) FROM allocation_input WHERE cutoff_at = :cutoffAt")
                    .param("cutoffAt", cutoffAt)
                    .query(Integer.class)
                    .single();
            cutoffRunRepository.markTargetFixed(cutoffAt, targetCount, inputQuantity);
        });
    }

    private void recordDiff(LocalDateTime cutoffAt, Comparison comparison) {
        for (String orderNo : comparison.missing()) {
            String kind = shipmentTargetRepository.exists(orderNo) ? "CANCELLED_MARK_BUT_PAID" : "MISSING_IN_TARGET";
            insertDiff(cutoffAt, orderNo, kind);
        }
        comparison.extra().forEach(orderNo -> insertDiff(cutoffAt, orderNo, "EXTRA_IN_TARGET"));
    }

    private void insertDiff(LocalDateTime cutoffAt, String orderNo, String kind) {
        jdbcClient.sql("""
                INSERT IGNORE INTO target_fix_diff (cutoff_at, order_no, kind, created_at)
                VALUES (:cutoffAt, :orderNo, :kind, NOW(3))
                """)
                .param("cutoffAt", cutoffAt)
                .param("orderNo", orderNo)
                .param("kind", kind)
                .update();
    }

    private void sleep() {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruptedException);
        }
    }

    private static List<List<String>> chunks(Set<String> orderNos) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>(IN_CLAUSE_SIZE);
        for (String orderNo : orderNos) {
            current.add(orderNo);
            if (current.size() == IN_CLAUSE_SIZE) {
                chunks.add(current);
                current = new ArrayList<>(IN_CLAUSE_SIZE);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private record Comparison(List<String> missing, List<String> extra, int fixedCount) {

        boolean matched() {
            return missing.isEmpty() && extra.isEmpty();
        }
    }
}
