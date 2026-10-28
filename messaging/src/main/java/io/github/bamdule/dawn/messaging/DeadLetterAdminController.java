package io.github.bamdule.dawn.messaging;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 처리 실패 토픽에 쌓인 메시지를 보고, 원인을 고친 뒤 원래 토픽에 다시 넣는다 (docs/design/03 "처리 실패")
@RestController
public class DeadLetterAdminController {

    private static final int DEFAULT_MAX_RECORDS = 500;

    private final DeadLetterReplayer deadLetterReplayer;
    private final DeadLetterSources deadLetterSources;

    public DeadLetterAdminController(DeadLetterReplayer deadLetterReplayer, DeadLetterSources deadLetterSources) {
        this.deadLetterReplayer = deadLetterReplayer;
        this.deadLetterSources = deadLetterSources;
    }

    @GetMapping("/admin/dead-letters")
    public List<DeadLetterBacklog> backlogs() {
        return deadLetterSources.originalTopics().stream()
                .map(topic -> new DeadLetterBacklog(topic, deadLetterReplayer.backlog(topic)))
                .toList();
    }

    // 이 서비스가 처리 실패 토픽을 선언하지 않은 토픽은 다시 넣지 않는다
    @PostMapping("/admin/dead-letters/{topic}/replay")
    public ResponseEntity<ReplayResponse> replay(@PathVariable String topic,
                                                 @RequestParam(defaultValue = "" + DEFAULT_MAX_RECORDS) int maxRecords) {
        if (!deadLetterSources.contains(topic)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ReplayResponse(deadLetterReplayer.replay(topic, maxRecords)));
    }

    public record DeadLetterBacklog(String topic, long backlog) {
    }

    public record ReplayResponse(int replayed) {
    }
}
