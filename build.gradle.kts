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

    // 운영은 서비스와 DB 를 모두 Asia/Seoul 로 둔다. 테스트 MySQL 도 같은 시간대라 테스트 JVM 도 맞춘다.
    // 맞추지 않으면 UTC 러너(CI)에서 JVM 시계로 만든 시각을 DB 시계와 비교하는 테스트가 9시간 어긋난다
    tasks.withType<Test> {
        useJUnitPlatform()
        systemProperty("user.timezone", "Asia/Seoul")
    }

    // 설계 가정을 재는 실험은 몇 분씩 걸려 기본 빌드에서 뺀다. ./gradlew experiment 로 따로 돌린다 (docs/experiments)
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("experiment")
        }
    }

    // API 문서 테스트(REST Docs)가 만든 Markdown 스니펫을 docs/api/{모듈}.md 하나로 합친다.
    // 스니펫 폴더 이름의 앞 번호가 문서 순서이고, 번호 뒤가 절 제목이다. 예시 값이 실행마다 달라 저장소 문서와 비교하지 않는다.
    // 계약이 바뀐 것은 REST Docs 가 잡는다. 문서에 없는 응답 필드가 생기거나 문서의 필드가 사라지면 문서 테스트가 깨진다
    val apiDocsModule = project.name
    val snippetDirectory = layout.buildDirectory.dir("generated-snippets")
    val apiDocsFile = rootProject.layout.projectDirectory.file("docs/api/$apiDocsModule.md")
    // 스니펫은 테스트의 산출물이다. 지워졌으면 테스트를 다시 돌려 만든다
    tasks.named<Test>("test") {
        outputs.dir(snippetDirectory)
    }

    tasks.register("apiDocs") {
        description = "API 문서 테스트의 스니펫을 docs/api 로 합친다"
        group = "documentation"
        dependsOn("test")
        // 테스트의 출력으로 선언해 둔 폴더는 문서 테스트가 없는 모듈에도 생긴다. 스니펫이 있을 때만 만든다
        onlyIf { snippetDirectory.get().asFile.listFiles { file -> file.isDirectory }.orEmpty().isNotEmpty() }
        doLast {
            val parts = listOf(
                "description" to null,
                "http-request" to "요청",
                "path-parameters" to null,
                "query-parameters" to null,
                "request-fields" to null,
                "http-response" to "응답",
                "response-fields" to null,
            )
            val sections = snippetDirectory.get().asFile.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
            val text = buildString {
                appendLine("# $apiDocsModule API")
                appendLine()
                appendLine("컨트롤러 테스트가 만든 문서입니다. 직접 고치지 말고 `./gradlew :$apiDocsModule:apiDocs` 로 다시 만듭니다.")
                sections.forEach { section ->
                    appendLine()
                    appendLine("## " + section.name.substringAfter('-'))
                    parts.forEach { (name, heading) ->
                        val snippet = section.resolve("$name.md")
                        if (snippet.isFile) {
                            appendLine()
                            if (heading != null) {
                                appendLine("**$heading**")
                                appendLine()
                            }
                            appendLine(snippet.readText().trim())
                        }
                    }
                }
            }
            apiDocsFile.asFile.parentFile.mkdirs()
            apiDocsFile.asFile.writeText(text)
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
