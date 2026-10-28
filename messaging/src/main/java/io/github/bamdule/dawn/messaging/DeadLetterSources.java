package io.github.bamdule.dawn.messaging;

import java.util.List;

// 이 서비스가 받는 토픽 가운데 처리 실패 토픽을 선언한 것들. 쌓인 수를 지표로 내고 관리 API 가 다시 넣을 수 있는 범위다
public record DeadLetterSources(List<String> originalTopics) {

    public boolean contains(String originalTopic) {
        return originalTopics.contains(originalTopic);
    }
}
