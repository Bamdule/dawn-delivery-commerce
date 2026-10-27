package io.github.bamdule.dawn.messaging.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// 잠금 동작을 확인하는 것이 목적이라 H2 가 아니라 운영과 같은 MySQL 8.4 를 띄운다
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainers {

    @Bean
    @ServiceConnection
    public MySQLContainer mySqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
    }
}
