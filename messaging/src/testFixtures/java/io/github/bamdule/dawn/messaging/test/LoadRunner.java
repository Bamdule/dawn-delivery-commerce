package io.github.bamdule.dawn.messaging.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

// 실험용 부하 도구. 작업은 성공이면 참, 품절처럼 정상적인 실패면 거짓을 돌려주고, 예외는 오류로 센다
public final class LoadRunner {

    private LoadRunner() {
    }

    // 일꾼 여럿이 쉬지 않고 보낸다. 그 자원이 낼 수 있는 상한을 잰다
    public static LoadResult closedLoop(int workers, Duration duration, BooleanSupplier task) throws InterruptedException {
        Recorder recorder = new Recorder();
        long deadline = System.nanoTime() + duration.toNanos();
        CountDownLatch done = new CountDownLatch(workers);
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < workers; worker++) {
                executorService.submit(() -> {
                    while (System.nanoTime() < deadline) {
                        recorder.run(task);
                    }
                    done.countDown();
                });
            }
            done.await();
        }
        return recorder.result(duration);
    }

    // 정해진 속도로 도착시킨다. 처리가 밀리면 대기가 쌓여 지연이 늘어난다. 실제 특가처럼 사용자는 기다려 주지 않는다
    public static LoadResult openLoop(int perSecond, Duration duration, BooleanSupplier task) throws InterruptedException {
        Recorder recorder = new Recorder();
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / perSecond;
        long total = duration.toSeconds() * perSecond;
        long startedAt = System.nanoTime();
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long index = 0; index < total; index++) {
                long arriveAt = startedAt + index * intervalNanos;
                long waitNanos = arriveAt - System.nanoTime();
                if (waitNanos > 0) {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                }
                executorService.submit(() -> recorder.run(task));
            }
        }
        return recorder.result(Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static final class Recorder {

        private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        private final AtomicInteger succeeded = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();

        void run(BooleanSupplier task) {
            long startedAt = System.nanoTime();
            try {
                if (task.getAsBoolean()) {
                    succeeded.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
            } catch (RuntimeException runtimeException) {
                errors.incrementAndGet();
            } finally {
                latencies.add(System.nanoTime() - startedAt);
            }
        }

        LoadResult result(Duration elapsed) {
            List<Long> sorted = new ArrayList<>(latencies);
            sorted.sort(Long::compare);
            return new LoadResult(succeeded.get(), rejected.get(), errors.get(), elapsed,
                    percentileMillis(sorted, 0.50), percentileMillis(sorted, 0.99), percentileMillis(sorted, 1.0));
        }

        private static double percentileMillis(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int index = (int) Math.min(sorted.size() - 1, Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(Math.max(index, 0)) / 1_000_000.0;
        }
    }

    public record LoadResult(int succeeded, int rejected, int errors, Duration elapsed, double p50Millis, double p99Millis,
                             double maxMillis) {

        public double succeededPerSecond() {
            return succeeded / (elapsed.toMillis() / 1000.0);
        }

        public String row(String label) {
            return "| %s | %,.0f | %,d | %,d | %.1f | %.1f | %.1f |".formatted(label, succeededPerSecond(), errors, rejected,
                    p50Millis, p99Millis, maxMillis);
        }

        public static String header() {
            return "| 방식 | 성공/초 | 오류 | 거절 | p50 ms | p99 ms | 최대 ms |\n|---|---|---|---|---|---|---|";
        }
    }
}
