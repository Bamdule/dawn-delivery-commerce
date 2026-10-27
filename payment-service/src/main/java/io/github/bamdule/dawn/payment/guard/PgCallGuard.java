package io.github.bamdule.dawn.payment.guard;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 대행사로 가는 모든 호출(승인, 조회, 환불)이 지나는 문. 동시 호출 한도와 회로 차단기를 함께 건다 (docs/design/01, 04)
@Component
public class PgCallGuard {

    static final Duration SLOW_CALL = Duration.ofSeconds(2);

    private final Semaphore permits;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public PgCallGuard(@Value("${dawn.pg.max-concurrent-calls:600}") int maxConcurrentCalls) {
        this(maxConcurrentCalls, new CircuitBreaker(Clock.systemUTC()));
    }

    PgCallGuard(int maxConcurrentCalls, CircuitBreaker circuitBreaker) {
        this.permits = new Semaphore(maxConcurrentCalls);
        this.circuitBreaker = circuitBreaker;
    }

    // 한도를 넘거나 회로가 열려 있으면 기다리지 않고 거절한다. 호출하지 않았으니 결과가 확실한 실패다
    public boolean tryEnter() {
        if (!permits.tryAcquire()) {
            return false;
        }
        if (!circuitBreaker.tryAcquirePermission()) {
            permits.release();
            return false;
        }
        return true;
    }

    public void exit(boolean failedOrUnknown, Duration elapsed) {
        permits.release();
        circuitBreaker.record(failedOrUnknown || elapsed.compareTo(SLOW_CALL) > 0);
    }

    public boolean isCircuitOpen() {
        return circuitBreaker.isOpen();
    }
}
