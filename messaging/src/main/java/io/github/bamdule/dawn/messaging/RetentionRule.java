package io.github.bamdule.dawn.messaging;

import java.time.Duration;
import java.util.Objects;

// 보존 기간이 지난 행을 지우는 규칙 하나. condition 은 :before(지금 - 보존 기간)를 쓰는 WHERE 절이다.
// 서비스는 자기 표의 규칙을 RetentionRules 하나로 묶어 등록하고, 자식 표 규칙을 부모 표보다 먼저 둔다 (docs/design/03 "보존과 정리")
public record RetentionRule(String name, String table, String condition, Duration retention) {

    public RetentionRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(retention, "retention");
    }
}
