// 서비스 네 개를 실제 프로세스로 띄워 장애를 섞어 넣는 실험만 둔다. 기본 빌드에서는 아무 테스트도 돌지 않는다 (docs/experiments)
dependencies {
    testImplementation(testFixtures(project(":messaging")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("tools.jackson.core:jackson-databind")
    testRuntimeOnly("com.mysql:mysql-connector-j")
}

val serviceJars = mapOf(
    "order" to ":order-service",
    "inventory" to ":inventory-service",
    "payment" to ":payment-service",
    "fakepg" to ":fake-pg",
)

tasks.named<Test>("experiment") {
    serviceJars.values.forEach { dependsOn("$it:bootJar") }
    serviceJars.forEach { (name, path) ->
        val service = project(path)
        systemProperty("dawn.jar.$name", service.layout.buildDirectory.file("libs/${service.name}-${service.version}.jar").get().asFile.absolutePath)
    }
    maxHeapSize = "1g"
}
