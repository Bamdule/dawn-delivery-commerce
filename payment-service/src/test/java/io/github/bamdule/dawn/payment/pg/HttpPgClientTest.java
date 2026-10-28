package io.github.bamdule.dawn.payment.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

// 대행사가 약속하지 않은 응답(4xx, 빈 본문)을 돌려줄 때 결제 서비스가 무엇으로 읽는지 본다
class HttpPgClientTest {

    private HttpServer httpServer;
    private volatile int nextStatus;
    private volatile String nextBody;

    @BeforeEach
    void startServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            byte[] body = nextBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        httpServer.start();
    }

    @AfterEach
    void stopServer() {
        httpServer.stop(0);
    }

    @Test
    void 조회의_404_는_요청_없음이고_그_밖의_4xx_와_빈_응답은_모름이다() {
        assertThat(inquireWith(404, "")).isEqualTo(PgClient.INQUIRY_NOT_FOUND);
        assertThat(inquireWith(400, "")).isEqualTo(PgClient.INQUIRY_UNKNOWN);
        assertThat(inquireWith(200, "")).isEqualTo(PgClient.INQUIRY_UNKNOWN);
        assertThat(inquireWith(200, "{\"status\":\"APPROVED\"}")).isEqualTo("APPROVED");
    }

    @Test
    void 승인의_4xx_는_거절이지만_409_와_빈_응답은_모름이다() {
        respond(400, "");
        PgApproveResult rejected = client().approve("key-1", 45_000, "CARD");
        respond(409, "");
        PgApproveResult inProgress = client().approve("key-1", 45_000, "CARD");
        respond(200, "");
        PgApproveResult empty = client().approve("key-1", 45_000, "CARD");

        assertThat(rejected.outcome()).isEqualTo(PgApproveResult.Outcome.DECLINED);
        assertThat(rejected.code()).isEqualTo("PG_4XX_400");
        assertThat(inProgress.outcome()).isEqualTo(PgApproveResult.Outcome.UNKNOWN);
        assertThat(empty.outcome()).isEqualTo(PgApproveResult.Outcome.UNKNOWN);
    }

    @Test
    void 환불의_4xx_는_거절이고_빈_응답은_모름이다() {
        respond(400, "");
        assertThat(client().refund("refund-1", "key-1", 45_000)).isEqualTo("DECLINED");
        respond(200, "");
        assertThat(client().refund("refund-1", "key-1", 45_000)).isEqualTo("UNKNOWN");
    }

    @Test
    void 연결을_맺지_못한_승인은_요청이_가지_않은_것이라_닿지_않음이다() {
        HttpPgClient closedPort = client();
        httpServer.stop(0);

        PgApproveResult result = closedPort.approve("key-1", 45_000, "CARD");

        assertThat(result.outcome()).isEqualTo(PgApproveResult.Outcome.NOT_SENT);
        assertThat(result.countsAsPgFailure()).as("회로 차단기는 대행사 장애로 센다").isTrue();
    }

    private String inquireWith(int status, String body) {
        respond(status, body);
        return client().inquire("key-1");
    }

    private void respond(int status, String body) {
        nextStatus = status;
        nextBody = body;
    }

    private HttpPgClient client() {
        return new HttpPgClient(RestClient.builder(), "http://localhost:" + httpServer.getAddress().getPort(),
                Duration.ofSeconds(2), true);
    }
}
