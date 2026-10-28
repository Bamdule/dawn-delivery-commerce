package io.github.bamdule.dawn.messaging.test;

// /actuator/prometheus 응답에서 한 지표의 값을 읽는다. 태그가 붙은 줄이 여럿이면 합한다
public final class PrometheusScrape {

    private PrometheusScrape() {
    }

    public static double value(String body, String metricName) {
        return body.lines()
                .filter(line -> line.startsWith(metricName + " ") || line.startsWith(metricName + "{"))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    public static boolean has(String body, String metricName) {
        return body.lines().anyMatch(line -> line.startsWith(metricName + " ") || line.startsWith(metricName + "{"));
    }
}
