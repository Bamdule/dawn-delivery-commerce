package io.github.bamdule.dawn.messaging.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// 잠금 동작을 확인하는 것이 목적이라 H2 가 아니라 운영과 같은 MySQL 8.4 를 띄운다.
// 테스트 설정이 다른 컨텍스트마다 컨테이너를 새로 띄우면 Docker 자원이 바닥나 기동에 수 분이 걸려서,
// JVM 하나에 한 벌만 띄우고 컨텍스트가 닫혀도 멈추지 않는다
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainers {

    private static final MySQLContainer MY_SQL_CONTAINER = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    static {
        MY_SQL_CONTAINER.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    public MySQLContainer mySqlContainer() {
        return MY_SQL_CONTAINER;
    }
}
