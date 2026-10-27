package io.github.bamdule.dawn.order;

import io.github.bamdule.dawn.order.placement.SoldOutProducts;
import io.github.bamdule.dawn.order.recovery.OrderCleanup;
import io.github.bamdule.dawn.order.refund.RefundSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 주기 작업의 시계만 모은다. 테스트는 이것을 끄고 각 작업을 직접 불러 순서를 정한다
@Component
@ConditionalOnProperty(prefix = "dawn.order.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderSchedules {

    private final SoldOutProducts soldOutProducts;
    private final OrderCleanup orderCleanup;
    private final RefundSender refundSender;

    public OrderSchedules(SoldOutProducts soldOutProducts, OrderCleanup orderCleanup, RefundSender refundSender) {
        this.soldOutProducts = soldOutProducts;
        this.orderCleanup = orderCleanup;
        this.refundSender = refundSender;
    }

    @Scheduled(fixedDelayString = "${dawn.order.sold-out-refresh-interval:500ms}")
    public void refreshSoldOut() {
        soldOutProducts.refresh();
    }

    @Scheduled(fixedDelayString = "${dawn.order.cleanup-interval:1m}")
    public void cleanup() {
        orderCleanup.run();
    }

    @Scheduled(fixedDelayString = "${dawn.order.refund-interval:1m}")
    public void sendRefunds() {
        refundSender.run();
    }
}
