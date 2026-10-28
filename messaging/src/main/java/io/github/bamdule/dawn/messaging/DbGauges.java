package io.github.bamdule.dawn.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// DB 상태를 세는 지표. 수집기가 긁을 때마다 DB 를 조회하지 않게 주기적으로 한 번 세어 두고 그 값을 낸다.
// 값이 DB 에서 나오므로 서비스를 다시 띄워도 사라지지 않는다 (docs/design/01 "운영 지표")
public class DbGauges {

    private static final Logger log = LoggerFactory.getLogger(DbGauges.class);

    private final MeterRegistry meterRegistry;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public DbGauges(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void register(String name, String description, String baseUnit, Supplier<Number> reader) {
        AtomicReference<Double> value = new AtomicReference<>(0.0);
        Gauge.builder(name, value, AtomicReference::get)
                .description(description)
                .baseUnit(baseUnit)
                .register(meterRegistry);
        entries.add(new Entry(name, reader, value));
    }

    // 한 지표의 조회가 실패해도 나머지는 갱신한다. 실패한 지표는 직전 값을 유지한다
    public void refresh() {
        for (Entry entry : entries) {
            try {
                Number number = entry.reader().get();
                entry.value().set(number == null ? 0.0 : number.doubleValue());
            } catch (RuntimeException runtimeException) {
                log.warn("지표 갱신 실패 (name={})", entry.name(), runtimeException);
            }
        }
    }

    private record Entry(String name, Supplier<Number> reader, AtomicReference<Double> value) {
    }
}
