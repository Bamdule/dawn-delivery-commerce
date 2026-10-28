package io.github.bamdule.dawn.inventory.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 출고 지시가 01:00 에 끝나므로 01:10 에 잰다. 출고 지시 전에 재면 실물 차감과 대기 수량 이동이 섞인 순간값을 본다
@Component
@ConditionalOnProperty(prefix = "dawn.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationSchedules {

    private final StockReconciler stockReconciler;

    public ReconciliationSchedules(StockReconciler stockReconciler) {
        this.stockReconciler = stockReconciler;
    }

    @Scheduled(cron = "${dawn.reconciliation.cron:0 10 1 * * *}", zone = "Asia/Seoul")
    public void reconcile() {
        stockReconciler.reconcileAll();
    }

    @Scheduled(fixedDelayString = "${dawn.reconciliation.recheck-interval:1m}")
    public void recheck() {
        stockReconciler.recheckSuspected();
    }
}
