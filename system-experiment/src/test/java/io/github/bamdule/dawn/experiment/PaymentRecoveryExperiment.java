package io.github.bamdule.dawn.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.test.ExperimentReport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실험 3. 주문, 재고, 결제, 가짜 대행사를 실제 프로세스로 띄우고 대행사 장애를 섞은 주문을 흘린다.
// 도중에 결제, 주문, 재고 서비스를 하나씩 SIGKILL 로 죽였다 다시 띄운 뒤, 세 서비스와 대행사의 상태가 모두 맞는지 본다 (docs/experiments/03-결제-복구.md)
@Tag("experiment")
class PaymentRecoveryExperiment {

    private static final int ORDERS = 300;
    private static final int ORDERS_PER_SECOND = 10;
    private static final int PRODUCTS = 20;
    private static final int INITIAL_STOCK = 1_000;
    private static final long UNIT_PRICE = 10_000;
    // 세 서비스를 겹치지 않게 하나씩 죽인다. 결제 6초, 주문 14초, 재고 22초에 SIGKILL 하고 6초 뒤 다시 띄운다
    private static final List<Duration> CRASH_AT = List.of(Duration.ofSeconds(6), Duration.ofSeconds(14), Duration.ofSeconds(22));
    private static final Duration DOWNTIME = Duration.ofSeconds(6);
    // 예약 응답을 받기 전에 주문 서비스가 죽으면 만료 시각을 몰라, 정리 배치가 주문 시각에 최대 유지 시간(10분)을 더해 기다린다
    private static final Duration CONVERGENCE_LIMIT = Duration.ofMinutes(13);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final long PRODUCT_BASE = 1_000;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Map<String, AtomicInteger> firstOutcomes = new ConcurrentHashMap<>();
    private final AtomicInteger clientRetries = new AtomicInteger();

    @Test
    void 대행사_장애와_주문_서비스_중단을_섞어도_결제와_주문과_예약이_모두_맞는다() throws Exception {
        ExperimentReport report = new ExperimentReport("03-payment-recovery");
        try (MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4")).withEnv("TZ", "Asia/Seoul");
             KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"))) {
            mysql.start();
            kafka.start();
            createDatabases(mysql);

            ServiceProcess fakePg = new ServiceProcess("fake-pg", "dawn.jar.fakepg", ServiceProcess.freePort(), List.of());
            int paymentPort = ServiceProcess.freePort();
            int inventoryPort = ServiceProcess.freePort();
            int orderPort = ServiceProcess.freePort();
            ServiceProcess payment = new ServiceProcess("payment-service", "dawn.jar.payment", paymentPort, args(mysql, kafka, "payment_db",
                    "--dawn.pg.base-url=" + fakePg.baseUrl(), "--dawn.payment.inquiry-interval=2s",
                    "--dawn.payment.refund-interval=2s", "--dawn.payment.settlement-cron=-"));
            ServiceProcess inventory = new ServiceProcess("inventory-service", "dawn.jar.inventory", inventoryPort, args(mysql, kafka, "inventory_db",
                    "--dawn.payment.base-url=http://localhost:" + paymentPort, "--dawn.reservation.expiry-interval=5s"));
            ServiceProcess order = new ServiceProcess("order-service", "dawn.jar.order", orderPort, args(mysql, kafka, "order_db",
                    "--dawn.inventory.base-url=http://localhost:" + inventoryPort, "--dawn.payment.base-url=http://localhost:" + paymentPort,
                    "--dawn.order.cleanup-interval=5s", "--dawn.order.refund-interval=3s"));
            List<ServiceProcess> services = List.of(fakePg, payment, inventory, order);
            try {
                for (ServiceProcess service : services) {
                    service.start();
                }
                seedStock(mysql);
                Map<String, Integer> behaviors = enqueueBehaviors(fakePg);
                report.line("## 준비");
                report.line("주문 %d건, 초당 %d건, 상품 %d개(재고 %d, 예약 유지 1분). 결제 %d초, 주문 %d초, 재고 %d초에 SIGKILL, 각각 %d초 뒤 재기동".formatted(
                        ORDERS, ORDERS_PER_SECOND, PRODUCTS, INITIAL_STOCK, CRASH_AT.get(0).toSeconds(), CRASH_AT.get(1).toSeconds(),
                        CRASH_AT.get(2).toSeconds(), DOWNTIME.toSeconds()));
                report.line("대행사 승인 동작 " + behaviors);

                long startedAt = System.nanoTime();
                runOrders(order, List.of(payment, order, inventory), startedAt);
                double loadSeconds = (System.nanoTime() - startedAt) / 1e9;
                double convergedAfter = awaitConvergence(mysql);

                report.line("");
                report.line("## 결과");
                report.line("부하 %.0f초, 부하가 끝난 뒤 수렴까지 %.0f초, 주문 서비스 중단으로 생긴 클라이언트 재시도 %d번".formatted(
                        loadSeconds, convergedAfter, clientRetries.get()));
                report.line("첫 응답 " + new LinkedHashMap<>(firstOutcomes));
                Map<String, Object> facts = check(mysql, fakePg, report);
                assertThat(facts.get("violations")).as("어긋난 주문이 없어야 한다").isEqualTo(List.of());
            } finally {
                for (ServiceProcess service : services.reversed()) {
                    service.stop();
                }
            }
        }
    }

    private void runOrders(ServiceProcess order, List<ServiceProcess> crashOrder, long startedAt) throws Exception {
        List<Future<?>> clients = new ArrayList<>();
        Random random = new Random(20261028);
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> crash = executorService.submit(() -> {
                for (int index = 0; index < crashOrder.size(); index++) {
                    sleepUntil(startedAt, CRASH_AT.get(index));
                    crashOrder.get(index).kill();
                    Thread.sleep(DOWNTIME.toMillis());
                    crashOrder.get(index).start();
                }
                return null;
            });
            for (int index = 0; index < ORDERS; index++) {
                sleepUntil(startedAt, Duration.ofMillis(1000L * index / ORDERS_PER_SECOND));
                long productId = PRODUCT_BASE + random.nextInt(PRODUCTS);
                clients.add(executorService.submit(() -> customer(order, productId)));
            }
            crash.get();
            for (Future<?> client : clients) {
                client.get();
            }
        }
    }

    // 고객 한 명. 응답을 못 받으면 같은 주문서 ID 로 다시 보내고, 카드가 거절되면 다른 카드로 한 번 더 결제한다
    private Void customer(ServiceProcess order, long productId) throws Exception {
        String checkoutId = UUID.randomUUID().toString();
        String body = """
                {"checkoutId": "%s", "memberId": 1, "recipientName": "김새벽", "address": "서울시 강남구 테헤란로 1",
                 "regionCode": "SEOUL", "paymentMethod": "CARD", "items": [{"productId": %d, "quantity": 1, "unitPrice": %d}]}
                """.formatted(checkoutId, productId, UNIT_PRICE);
        JsonNode placed = postWithRetry(order.baseUrl() + "/orders", body);
        String outcome = placed.get("outcome").asString();
        firstOutcomes.computeIfAbsent(outcome, key -> new AtomicInteger()).incrementAndGet();
        if (List.of("PAYMENT_DECLINED", "PAYMENT_DELAYED", "RETRY_PAYMENT").contains(outcome)) {
            postWithRetry(order.baseUrl() + "/orders/" + placed.get("orderNo").asString() + "/payments", "{\"paymentMethod\": \"OTHER_CARD\"}");
        }
        return null;
    }

    private JsonNode postWithRetry(String url, String body) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(15))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return jsonMapper.readTree(response.body());
                }
            } catch (java.io.IOException connectionLost) {
                // 주문 서비스가 죽어 있거나 응답 전에 죽었다
            }
            if (attempt == 60) {
                throw new IllegalStateException("주문 서비스가 1분 넘게 답하지 않았습니다.");
            }
            clientRetries.incrementAndGet();
            Thread.sleep(1_000);
        }
    }

    // 섞을 장애는 고정한 시드로 섞어 실험을 다시 돌려도 같은 순서다. 줄을 다 쓰면 대행사는 정상 승인으로 돌아간다
    private Map<String, Integer> enqueueBehaviors(ServiceProcess fakePg) throws Exception {
        Map<String, Integer> mix = new LinkedHashMap<>();
        mix.put("APPROVE", 210);
        mix.put("DECLINE", 30);
        mix.put("APPROVE_SLOWLY", 20);
        mix.put("ERROR_BEFORE_PROCESSING", 15);
        mix.put("ERROR_AFTER_APPROVE", 15);
        mix.put("APPROVE_LATE", 20);
        mix.put("NEVER_RECEIVED", 15);
        List<String> queue = new ArrayList<>();
        mix.forEach((behavior, count) -> queue.addAll(Collections.nCopies(count, behavior)));
        Collections.shuffle(queue, new Random(20261028));
        for (String behavior : queue) {
            long delayMillis = switch (behavior) {
                case "APPROVE_SLOWLY" -> 5_000;
                case "APPROVE_LATE" -> 20_000;
                default -> 0;
            };
            httpClient.send(HttpRequest.newBuilder(URI.create(fakePg.baseUrl() + "/fake/behaviors/approve?behavior=" + behavior
                    + "&delayMillis=" + delayMillis)).timeout(HTTP_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        return mix;
    }

    // 멈춘 상태가 하나도 없을 때까지 기다린다. 각 상태의 주인(정리 배치, 조회 배치, 만료 배치, 릴레이)이 끝내야 한다
    private double awaitConvergence(MySQLContainer mysql) throws Exception {
        long startedAt = System.nanoTime();
        while (System.nanoTime() - startedAt < CONVERGENCE_LIMIT.toNanos()) {
            long unsettled = count(mysql, "order_db", "SELECT COUNT(*) FROM orders WHERE status = 'PENDING'")
                    + count(mysql, "order_db", "SELECT COUNT(*) FROM refund_request WHERE status IN ('REQUESTED', 'ACCEPTED')")
                    + count(mysql, "order_db", "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'")
                    + count(mysql, "payment_db", "SELECT COUNT(*) FROM payment WHERE status = 'REQUESTED'")
                    + count(mysql, "payment_db", "SELECT COUNT(*) FROM refund WHERE status = 'REQUESTED'")
                    + count(mysql, "payment_db", "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'")
                    + count(mysql, "inventory_db", "SELECT COUNT(*) FROM stock_reservation WHERE status IN ('HELD', 'PAYING')");
            if (unsettled == 0) {
                return (System.nanoTime() - startedAt) / 1e9;
            }
            Thread.sleep(5_000);
        }
        return -1;
    }

    // 주문마다 대행사에서 빠져나간 돈(승인 - 완료된 환불)이 PAID 면 주문 금액, 아니면 0 이어야 한다.
    // PAID 주문은 예약이 확정되고, 그 밖의 주문은 예약이 풀려야 한다. 상품마다 처음 재고 = 남은 재고 + 확정 수량이어야 한다
    private Map<String, Object> check(MySQLContainer mysql, ServiceProcess fakePg, ExperimentReport report) throws Exception {
        JsonNode settlement = jsonMapper.readTree(httpClient.send(HttpRequest.newBuilder(URI.create(
                fakePg.baseUrl() + "/pg/settlements?date=" + LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))).timeout(HTTP_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        Map<String, Long> charged = new HashMap<>();
        Map<String, Integer> approvals = new HashMap<>();
        for (JsonNode approval : settlement.get("approvals")) {
            String orderNo = orderNoOf(approval.get("idempotencyKey").asString());
            charged.merge(orderNo, approval.get("amount").asLong(), Long::sum);
            approvals.merge(orderNo, 1, Integer::sum);
        }
        int completedRefunds = 0;
        for (JsonNode refund : settlement.get("refunds")) {
            if ("COMPLETED".equals(refund.get("status").asString())) {
                charged.merge(orderNoOf(refund.get("paymentKey").asString()), -refund.get("amount").asLong(), Long::sum);
                completedRefunds++;
            }
        }
        Map<String, String> orders = new HashMap<>();
        query(mysql, "order_db", "SELECT order_no, status FROM orders", rs -> orders.put(rs.getString(1), rs.getString(2)));
        Map<String, String> reservations = new HashMap<>();
        query(mysql, "inventory_db", "SELECT order_no, status FROM stock_reservation", rs -> reservations.put(rs.getString(1), rs.getString(2)));

        List<String> violations = new ArrayList<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : orders.entrySet()) {
            String orderNo = entry.getKey();
            String status = entry.getValue();
            byStatus.merge(status, 1, Integer::sum);
            long expectedCharge = status.equals("PAID") ? UNIT_PRICE : 0;
            long actualCharge = charged.getOrDefault(orderNo, 0L);
            String reservation = reservations.getOrDefault(orderNo, "NONE");
            boolean reservationOk = status.equals("PAID") ? reservation.equals("CONFIRMED") : !reservation.equals("CONFIRMED")
                    && !reservation.equals("HELD") && !reservation.equals("PAYING");
            if (actualCharge != expectedCharge || !reservationOk) {
                violations.add("%s %s 결제 %d 예약 %s".formatted(orderNo, status, actualCharge, reservation));
            }
        }
        long stockGap = count(mysql, "inventory_db", """
                SELECT COALESCE(SUM(ABS(%d - s.quantity - COALESCE(r.confirmed, 0))), 0) FROM stock s
                  LEFT JOIN (SELECT product_id, SUM(quantity - cancelled_quantity) AS confirmed FROM stock_reservation
                              WHERE status = 'CONFIRMED' GROUP BY product_id) r ON r.product_id = s.product_id
                 WHERE s.product_id BETWEEN %d AND %d
                """.formatted(INITIAL_STOCK, PRODUCT_BASE, PRODUCT_BASE + PRODUCTS - 1));
        if (stockGap != 0) {
            violations.add("재고 합이 %d 어긋남".formatted(stockGap));
        }
        long orphanRefunds = count(mysql, "order_db", "SELECT COUNT(*) FROM refund_request WHERE reason = 'ORPHAN_APPROVAL'");
        long multipleApprovals = approvals.values().stream().filter(count -> count > 1).count();

        report.line("최종 주문 상태 " + byStatus);
        report.line("대행사 승인 %d건, 완료된 환불 %d건, 승인이 둘 이상인 주문 %d건, 쓰이지 않은 승인 환불 요청 %d건".formatted(
                approvals.values().stream().mapToInt(Integer::intValue).sum(), completedRefunds, multipleApprovals, orphanRefunds));
        report.line("재고 합 어긋남 %d, 어긋난 주문 %d건".formatted(stockGap, violations.size()));
        violations.stream().limit(20).forEach(violation -> report.line("- " + violation));
        return Map.of("violations", violations);
    }

    private static String orderNoOf(String key) {
        // order-{주문번호}-payment-{회차}
        return key.substring("order-".length(), key.lastIndexOf("-payment-"));
    }

    private static List<String> args(MySQLContainer mysql, KafkaContainer kafka, String database, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database,
                "--spring.datasource.username=root",
                "--spring.datasource.password=" + mysql.getPassword(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers()));
        args.addAll(List.of(extra));
        return args;
    }

    private static void createDatabases(MySQLContainer mysql) throws SQLException {
        try (Connection connection = root(mysql, ""); Statement statement = connection.createStatement()) {
            for (String database : List.of("order_db", "inventory_db", "payment_db")) {
                statement.execute("CREATE DATABASE " + database);
            }
        }
    }

    private static void seedStock(MySQLContainer mysql) throws SQLException {
        try (Connection connection = root(mysql, "inventory_db"); Statement statement = connection.createStatement()) {
            for (int index = 0; index < PRODUCTS; index++) {
                statement.execute("INSERT INTO stock (product_id, quantity, slot_mode, hold_minutes, deficit, updated_at) VALUES (%d, %d, 'NONE', 1, 0, NOW(3))"
                        .formatted(PRODUCT_BASE + index, INITIAL_STOCK));
            }
        }
    }

    private static long count(MySQLContainer mysql, String database, String sql) throws SQLException {
        try (Connection connection = root(mysql, database); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void query(MySQLContainer mysql, String database, String sql, RowHandler rowHandler) throws SQLException {
        try (Connection connection = root(mysql, database); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rowHandler.handle(resultSet);
            }
        }
    }

    private static Connection root(MySQLContainer mysql, String database) throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database,
                "root", mysql.getPassword());
    }

    private static void sleepUntil(long startedAt, Duration offset) throws InterruptedException {
        long waitNanos = startedAt + offset.toNanos() - System.nanoTime();
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    @FunctionalInterface
    private interface RowHandler {
        void handle(ResultSet resultSet) throws SQLException;
    }
}
