package io.github.bamdule.dawn.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({MySqlContainers.class, KafkaContainers.class})
class StockDeductionConcurrencyTest {

    @Autowired
    private StockDeduction stockDeduction;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 조회하고_계산해서_쓰면_10건이_모두_성공해도_재고는_1만_줄어든다() throws Exception {
        long productId = StockFixture.newProduct(jdbcClient, 100);
        int requests = 10;
        CyclicBarrier everyoneHasRead = new CyclicBarrier(requests);

        // 설계에서 고르지 않은 방식이라 테스트 코드 안에만 둔다. 모두 100 을 읽은 뒤 각자 99 를 쓴다
        int succeeded = runConcurrently(requests, requests, () -> transactionTemplate.execute(status -> {
            int read = StockFixture.quantityOf(jdbcClient, productId);
            await(everyoneHasRead);
            jdbcClient.sql("UPDATE stock SET quantity = :quantity WHERE product_id = :productId")
                    .param("quantity", read - 1)
                    .param("productId", productId)
                    .update();
            return true;
        }));

        assertThat(succeeded).isEqualTo(10);
        assertThat(StockFixture.quantityOf(jdbcClient, productId)).as("10개를 팔았는데 1개만 줄었다").isEqualTo(99);
    }

    @Test
    void 재고_100개에_300건이_동시에_오면_100건만_성공하고_재고는_0이_된다() throws Exception {
        long productId = StockFixture.newProduct(jdbcClient, 100);

        int succeeded = runConcurrently(300, 50, () -> {
            try {
                stockDeduction.deduct(List.of(new StockLine(productId, 1)));
                return true;
            } catch (OutOfStockException outOfStockException) {
                return false;
            }
        });

        assertThat(succeeded).isEqualTo(100);
        assertThat(StockFixture.quantityOf(jdbcClient, productId)).isZero();
    }

    @Test
    void 상품_순서가_반대인_주문_1000건을_섞어도_교착_없이_모두_차감된다() throws Exception {
        long productA = StockFixture.newProduct(jdbcClient, 10_000);
        long productB = StockFixture.newProduct(jdbcClient, 10_000);
        AtomicInteger sequence = new AtomicInteger();

        int succeeded = runConcurrently(1_000, 20, () -> {
            List<StockLine> lines = sequence.incrementAndGet() % 2 == 0
                    ? List.of(new StockLine(productA, 1), new StockLine(productB, 1))
                    : List.of(new StockLine(productB, 1), new StockLine(productA, 1));
            stockDeduction.deduct(lines);
            return true;
        });

        assertThat(succeeded).isEqualTo(1_000);
        assertThat(StockFixture.quantityOf(jdbcClient, productA)).isEqualTo(9_000);
        assertThat(StockFixture.quantityOf(jdbcClient, productB)).isEqualTo(9_000);
    }

    @Test
    void 여러_상품_중_하나라도_모자라면_아무것도_차감하지_않는다() {
        long enough = StockFixture.newProduct(jdbcClient, 10);
        long shortage = StockFixture.newProduct(jdbcClient, 1);

        assertThatThrownBy(() -> stockDeduction.deduct(List.of(new StockLine(enough, 3), new StockLine(shortage, 2))))
                .isInstanceOf(OutOfStockException.class)
                .extracting(exception -> ((OutOfStockException) exception).getProductId())
                .isEqualTo(shortage);

        assertThat(StockFixture.quantityOf(jdbcClient, enough)).isEqualTo(10);
        assertThat(StockFixture.quantityOf(jdbcClient, shortage)).isEqualTo(1);
    }

    // 모든 요청을 한꺼번에 출발시키고, 예외 없이 true 를 돌려준 요청 수를 센다
    private int runConcurrently(int requests, int threads, Callable<Boolean> request) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(requests);
        for (int index = 0; index < requests; index++) {
            futures.add(executorService.submit(() -> {
                start.await();
                return request.call();
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.get(60, TimeUnit.SECONDS))) {
                succeeded++;
            }
        }
        executorService.shutdown();
        return succeeded;
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
