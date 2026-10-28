package io.github.bamdule.dawn.messaging;

import java.util.List;

// 한 서비스의 보존 규칙을 지울 순서대로 담는다. 순서가 곧 자식 표를 먼저 지우는 순서다
public record RetentionRules(List<RetentionRule> rules) {

    public RetentionRules {
        rules = List.copyOf(rules);
    }
}
