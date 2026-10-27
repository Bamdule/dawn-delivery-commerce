package io.github.bamdule.dawn.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

// 같은 사실에는 늘 같은 아이디가 나와야 받는 쪽이 중복을 거를 수 있다 (docs/design/03)
public final class EventIds {

    private EventIds() {
    }

    public static UUID of(String... parts) {
        if (parts.length == 0) {
            throw new IllegalArgumentException("이벤트 아이디를 만들 값이 없습니다.");
        }
        return UUID.nameUUIDFromBytes(String.join(":", parts).getBytes(StandardCharsets.UTF_8));
    }
}
