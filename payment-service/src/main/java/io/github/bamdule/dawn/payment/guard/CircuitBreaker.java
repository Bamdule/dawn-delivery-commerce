package io.github.bamdule.dawn.payment.guard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

// 최근 100건 중 50% 이상이 타임아웃, 5xx, 2초 넘는 느린 호출이면 연다. 카드 거절은 대행사가 정상으로 답한 것이라 세지 않는다.
// 30초 뒤 10건만 흘려 보고, 하나라도 실패하면 다시 연다 (docs/design/04 "회로 차단기")
public class CircuitBreaker {

    static final int WINDOW_SIZE = 100;
    static final double FAILURE_RATE_THRESHOLD = 0.5;
    static final Duration OPEN_DURATION = Duration.ofSeconds(30);
    static final int HALF_OPEN_TRIALS = 10;

    private final Clock clock;
    private final boolean[] window = new boolean[WINDOW_SIZE];
    private int recorded;
    private int cursor;
    private int failures;
    private State state = State.CLOSED;
    private Instant openedAt;
    private int trialsStarted;
    private int trialsSucceeded;

    public CircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean tryAcquirePermission() {
        if (state == State.OPEN) {
            if (Duration.between(openedAt, clock.instant()).compareTo(OPEN_DURATION) < 0) {
                return false;
            }
            state = State.HALF_OPEN;
            trialsStarted = 0;
            trialsSucceeded = 0;
        }
        if (state == State.HALF_OPEN) {
            if (trialsStarted >= HALF_OPEN_TRIALS) {
                return false;
            }
            trialsStarted++;
        }
        return true;
    }

    public synchronized void record(boolean failed) {
        if (state == State.HALF_OPEN) {
            if (failed) {
                open();
                return;
            }
            trialsSucceeded++;
            if (trialsSucceeded >= HALF_OPEN_TRIALS) {
                close();
            }
            return;
        }
        if (recorded == WINDOW_SIZE && window[cursor]) {
            failures--;
        }
        window[cursor] = failed;
        if (failed) {
            failures++;
        }
        cursor = (cursor + 1) % WINDOW_SIZE;
        recorded = Math.min(recorded + 1, WINDOW_SIZE);
        if (recorded == WINDOW_SIZE && failures >= WINDOW_SIZE * FAILURE_RATE_THRESHOLD) {
            open();
        }
    }

    public synchronized boolean isOpen() {
        return state == State.OPEN && Duration.between(openedAt, clock.instant()).compareTo(OPEN_DURATION) < 0;
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
    }

    private void close() {
        state = State.CLOSED;
        recorded = 0;
        cursor = 0;
        failures = 0;
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
