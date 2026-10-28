package io.github.bamdule.dawn.messaging.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// 실험 결과를 표준 출력과 build/experiment-results/{이름}.md 에 함께 남긴다. 문서는 이 파일에서 옮겨 쓴다
public final class ExperimentReport {

    private final Path file;

    public ExperimentReport(String name) {
        this.file = Path.of("build", "experiment-results", name + ".md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "");
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }

    public void line(String text) {
        System.out.println("[실험] " + text);
        try {
            Files.writeString(file, text + System.lineSeparator(), StandardOpenOption.APPEND);
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }
}
