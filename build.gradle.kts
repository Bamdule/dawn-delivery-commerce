plugins {
    java
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "io.github.bamdule.dawn"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    // 라이브러리 모듈(messaging)은 Boot 플러그인이 없어 인자 이름이 빠진다. @PathVariable 같은 이름 기반 바인딩이 읽게 남긴다
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // 설계 가정을 재는 실험은 몇 분씩 걸려 기본 빌드에서 뺀다. ./gradlew experiment 로 따로 돌린다 (docs/experiments)
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("experiment")
        }
    }

    val testSourceSet = the<SourceSetContainer>()["test"]
    tasks.register<Test>("experiment") {
        description = "설계 가정을 재는 실험을 돌린다"
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags("experiment")
        }
        outputs.upToDateWhen { false }
        maxHeapSize = "2g"
        testLogging {
            showStandardStreams = true
        }
    }
}
