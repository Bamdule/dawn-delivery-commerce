package io.github.bamdule.dawn.payment.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(clock);

    @Test
    void 최근_100건_중_50건이_실패하면_열리고_30초_동안_거절한다() {
        record(50, false);
        record(49, true);
        assertThat(circuitBreaker.tryAcquirePermission()).as("49건 실패는 아직 닫혀 있다").isTrue();

        record(1, true);

        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void 열린_뒤_30초가_지나면_10건만_흘려보고_모두_성공하면_닫힌다() {
        record(100, true);
        clock.advance(Duration.ofSeconds(30));

        for (int trial = 0; trial < 10; trial++) {
            assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        }
        assertThat(circuitBreaker.tryAcquirePermission()).as("시험 호출은 10건까지").isFalse();
        record(10, false);

        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.isOpen()).isFalse();
    }

    @Test
    void 시험_호출이_하나라도_실패하면_다시_연다() {
        record(100, true);
        clock.advance(Duration.ofSeconds(30));
        circuitBreaker.tryAcquirePermission();

        circuitBreaker.record(true);

        assertThat(circuitBreaker.isOpen()).isTrue();
    }

    @Test
    void 동시_호출_한도를_넘으면_기다리지_않고_거절한다() {
        PgCallGuard pgCallGuard = new PgCallGuard(2, circuitBreaker);

        assertThat(pgCallGuard.tryEnter()).isTrue();
        assertThat(pgCallGuard.tryEnter()).isTrue();
        assertThat(pgCallGuard.tryEnter()).isFalse();
        pgCallGuard.exit(false, Duration.ofMillis(300));
        assertThat(pgCallGuard.tryEnter()).isTrue();
    }

    @Test
    void 느린_호출_2초_초과는_실패로_세고_카드_거절처럼_정상_응답은_세지_않는다() {
        PgCallGuard pgCallGuard = new PgCallGuard(600, circuitBreaker);
        for (int call = 0; call < 100; call++) {
            pgCallGuard.tryEnter();
            pgCallGuard.exit(false, Duration.ofMillis(2_500));
        }

        assertThat(pgCallGuard.isCircuitOpen()).isTrue();
    }

    private void record(int count, boolean failed) {
        for (int index = 0; index < count; index++) {
            circuitBreaker.record(failed);
        }
    }

    private static class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-28T13:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
