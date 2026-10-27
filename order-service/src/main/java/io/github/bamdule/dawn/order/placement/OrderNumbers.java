package io.github.bamdule.dawn.order.placement;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

// 주문번호는 서버가 만든다. 결제 멱등키가 이 번호로 만들어지므로 같은 주문 요청의 재시도는 주문서 ID 로 묶는다 (docs/design/01)
@Component
public class OrderNumbers {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_LENGTH = 10;

    private final SecureRandom secureRandom = new SecureRandom();

    public String next() {
        StringBuilder orderNo = new StringBuilder(LocalDate.now(SEOUL).format(DATE_FORMAT)).append('-');
        for (int index = 0; index < RANDOM_LENGTH; index++) {
            orderNo.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return orderNo.toString();
    }
}
