package io.github.bamdule.dawn.messaging;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

// Spring Batch 잡으로 만들면 지울 테이블에 자기 실행 이력을 남겨서 일반 스케줄 컴포넌트로 둔다
public class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);
    private static final Duration SENT_RETENTION = Duration.ofDays(7);

    private final OutboxMaintenance outboxMaintenance;

    public OutboxCleanupScheduler(OutboxMaintenance outboxMaintenance) {
        this.outboxMaintenance = outboxMaintenance;
    }

    @Scheduled(cron = "${dawn.outbox.cleanup.cron:0 30 4 * * *}")
    public void cleanup() {
        int deleted = outboxMaintenance.deleteSentOlderThan(SENT_RETENTION);
        if (deleted > 0) {
            log.info("아웃박스 보존 기간 정리 (deleted={})", deleted);
        }
    }
}
