plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("io.micrometer:micrometer-core")
    // 관리 API 는 웹 서비스에서만 켜진다. 웹이 없는 곳에 끌고 가지 않는다
    compileOnly("org.springframework:spring-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.mysql:mysql-connector-j")

    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
    testFixturesApi("org.testcontainers:testcontainers-mysql")
    testFixturesApi("org.testcontainers:testcontainers-kafka")
    // 서비스의 API 문서 테스트가 함께 쓰는 형식과 템플릿 (docs/api)
    testFixturesApi("org.springframework.boot:spring-boot-starter-restdocs")
    testFixturesApi("org.springframework.restdocs:spring-restdocs-mockmvc")
}
