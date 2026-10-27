package io.github.bamdule.dawn.messaging;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

// 보존 기간이 지난 SENT 를 지우고, 사람이 영구히 보낼 수 없는 이벤트를 격리한다 (docs/design/03)
public class OutboxMaintenance {

    private static final int DELETE_CHUNK_SIZE = 1_000;

    private final JdbcClient jdbcClient;

    public OutboxMaintenance(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 큰 DELETE 한 번은 락과 복제 지연을 만들어서 1,000행씩 나눠 지운다
    public int deleteSentOlderThan(Duration retention) {
        int deletedTotal = 0;
        int deleted;
        do {
            deleted = jdbcClient.sql("""
                    DELETE FROM outbox_event
                     WHERE status = 'SENT' AND sent_at < NOW(3) - INTERVAL :seconds SECOND
                     LIMIT :limit
                    """)
                    .param("seconds", retention.toSeconds())
                    .param("limit", DELETE_CHUNK_SIZE)
                    .update();
            deletedTotal += deleted;
        } while (deleted == DELETE_CHUNK_SIZE);
        return deletedTotal;
    }

    // 격리하면 같은 키의 뒤 이벤트가 풀린다. 순서가 바뀌어도 되는지 사람이 판단한 뒤에만 부른다
    public boolean quarantine(long seq) {
        return jdbcClient.sql("UPDATE outbox_event SET status = 'QUARANTINED' WHERE seq = :seq AND status = 'PENDING'")
                .param("seq", seq)
                .update() == 1;
    }
}
