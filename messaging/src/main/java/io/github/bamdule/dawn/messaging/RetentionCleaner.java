package io.github.bamdule.dawn.messaging;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

// 큰 DELETE 한 번은 락과 복제 지연을 만들어서 1,000행씩 나눠 지운다. 기준 시각은 DB 시계로 정한다
public class RetentionCleaner {

    private static final int DELETE_CHUNK_SIZE = 1_000;

    private final JdbcClient jdbcClient;

    public RetentionCleaner(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int clean(RetentionRule retentionRule) {
        LocalDateTime before = jdbcClient.sql("SELECT NOW(3) - INTERVAL :seconds SECOND")
                .param("seconds", retentionRule.retention().toSeconds())
                .query(LocalDateTime.class)
                .single();
        String sql = "DELETE FROM " + retentionRule.table() + " WHERE " + retentionRule.condition() + " LIMIT " + DELETE_CHUNK_SIZE;
        int deletedTotal = 0;
        int deleted;
        do {
            deleted = jdbcClient.sql(sql).param("before", before).update();
            deletedTotal += deleted;
        } while (deleted == DELETE_CHUNK_SIZE);
        return deletedTotal;
    }
}
