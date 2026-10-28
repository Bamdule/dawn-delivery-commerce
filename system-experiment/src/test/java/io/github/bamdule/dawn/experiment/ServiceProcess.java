package io.github.bamdule.dawn.experiment;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// 서비스 하나를 실행 jar 로 띄운 별도 프로세스. 죽일 때는 운영의 갑작스러운 중단처럼 SIGKILL 을 보낸다
final class ServiceProcess {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final String name;
    private final Path jar;
    private final List<String> arguments;
    private final int port;
    private final Path log;
    private Process process;

    ServiceProcess(String name, String jarProperty, int port, List<String> arguments) {
        this.name = name;
        this.jar = Path.of(System.getProperty(jarProperty));
        this.port = port;
        this.arguments = List.copyOf(arguments);
        this.log = Path.of("build", "experiment-results", name + ".log");
    }

    static int freePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    void start() throws Exception {
        Files.createDirectories(log.getParent());
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"),
                "-Xmx512m", "-jar", jar.toString(), "--server.port=" + port, "--spring.main.banner-mode=off"));
        command.addAll(arguments);
        process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        awaitHealthy();
    }

    void kill() throws InterruptedException {
        process.destroyForcibly();
        process.waitFor();
    }

    void stop() throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroy();
            process.waitFor();
        }
    }

    private void awaitHealthy() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " 기동 실패. 로그: " + log.toAbsolutePath());
            }
            try {
                HttpResponse<String> response = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // 아직 포트를 열지 않았다
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(name + " 이 2분 안에 뜨지 않았습니다. 로그: " + log.toAbsolutePath());
    }
}
