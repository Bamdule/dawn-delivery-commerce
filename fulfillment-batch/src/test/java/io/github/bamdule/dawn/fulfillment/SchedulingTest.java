package io.github.bamdule.dawn.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

class SchedulingTest extends FulfillmentIntegrationTest {

    @Autowired
    private TaskScheduler taskScheduler;

    // 스레드 하나짜리 기본 스케줄러면 23:10 출고 잡이 도는 10여 분 동안 릴레이와 지표 갱신이 멈춘다
    @Test
    void 주기_작업은_서로를_기다리지_않는_가상_스레드_스케줄러로_돈다() throws Exception {
        assertThat(taskScheduler).isInstanceOf(SimpleAsyncTaskScheduler.class);

        var started = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        Runnable blocking = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        };
        taskScheduler.schedule(blocking, java.time.Instant.now());
        taskScheduler.schedule(blocking, java.time.Instant.now());

        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("앞 작업이 끝나지 않아도 다음 작업이 시작된다").isTrue();
        release.countDown();
    }
}
