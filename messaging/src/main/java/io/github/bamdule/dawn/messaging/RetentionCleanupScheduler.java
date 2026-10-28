package io.github.bamdule.dawn.messaging;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

// Spring Batch 잡으로 만들면 지울 테이블에 자기 실행 이력을 남겨서 일반 스케줄 컴포넌트로 둔다.
// 한 규칙이 실패해도 나머지 규칙은 지운다. 실패한 규칙은 다음 날 다시 한다
public class RetentionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupScheduler.class);

    private final RetentionCleaner retentionCleaner;
    private final List<RetentionRule> retentionRules;

    public RetentionCleanupScheduler(RetentionCleaner retentionCleaner, List<RetentionRule> retentionRules) {
        this.retentionCleaner = retentionCleaner;
        this.retentionRules = List.copyOf(retentionRules);
    }

    @Scheduled(cron = "${dawn.retention.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void cleanup() {
        for (RetentionRule retentionRule : retentionRules) {
            try {
                int deleted = retentionCleaner.clean(retentionRule);
                if (deleted > 0) {
                    log.info("보존 기간 정리 (rule={}, deleted={})", retentionRule.name(), deleted);
                }
            } catch (RuntimeException runtimeException) {
                log.error("보존 기간 정리 실패 (rule={})", retentionRule.name(), runtimeException);
            }
        }
    }
}
