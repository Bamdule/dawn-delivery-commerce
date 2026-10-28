package io.github.bamdule.dawn.messaging;

import io.github.bamdule.dawn.messaging.OutboxMaintenance.StuckEvent;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 영구히 보낼 수 없는 이벤트를 사람이 다룬다. 원인을 고쳐 바로 다시 보내거나, 격리해 같은 키의 뒤 이벤트를 푼다 (docs/design/03)
@RestController
public class OutboxAdminController {

    private static final int LIST_LIMIT = 100;

    private final OutboxMaintenance outboxMaintenance;
    private final OutboxProperties outboxProperties;

    public OutboxAdminController(OutboxMaintenance outboxMaintenance, OutboxProperties outboxProperties) {
        this.outboxMaintenance = outboxMaintenance;
        this.outboxProperties = outboxProperties;
    }

    // 경보 기준(10번)과 같은 횟수 이상 실패한 이벤트
    @GetMapping("/admin/outbox/stuck")
    public List<StuckEvent> stuck() {
        return outboxMaintenance.findStuck(outboxProperties.alertAttemptCount(), LIST_LIMIT);
    }

    @GetMapping("/admin/outbox/quarantined")
    public List<StuckEvent> quarantined() {
        return outboxMaintenance.findQuarantined(LIST_LIMIT);
    }

    @PostMapping("/admin/outbox/{seq}/retry")
    public ResponseEntity<Void> retry(@PathVariable long seq) {
        return outboxMaintenance.retryNow(seq) ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }

    @PostMapping("/admin/outbox/{seq}/quarantine")
    public ResponseEntity<Void> quarantine(@PathVariable long seq) {
        return outboxMaintenance.quarantine(seq) ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }
}
