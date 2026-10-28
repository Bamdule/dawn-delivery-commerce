package io.github.bamdule.dawn.fulfillment.shipment;

import io.github.bamdule.dawn.fulfillment.run.CutoffRunRepository;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

// 배차가 VERIFIED 회차를 가져갈 때 그 회차의 출고 지시를 넘긴다 (docs/design/05 "결과 검증").
// 넘긴 출고 지시는 물건이 선반을 떠나므로 실사가 피킹 전 수량으로 빼지 않고, 출고 지시 취소도 받지 않는다
@Service
public class ShipmentHandover {

    private final CutoffRunRepository cutoffRunRepository;
    private final JdbcClient jdbcClient;

    public ShipmentHandover(CutoffRunRepository cutoffRunRepository, JdbcClient jdbcClient) {
        this.cutoffRunRepository = cutoffRunRepository;
        this.jdbcClient = jdbcClient;
    }

    // 검증을 통과하지 않은 회차는 넘기지 않는다. 같은 회차를 다시 넘기면 0건이다
    public Optional<Integer> handOver(LocalDateTime cutoffAt) {
        if (cutoffRunRepository.findStatus(cutoffAt).filter(status -> status == CutoffRunStatus.VERIFIED).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jdbcClient.sql("UPDATE shipment_order SET status = 'HANDED_OVER' WHERE cutoff_at = :cutoffAt AND status = 'CREATED'")
                .param("cutoffAt", cutoffAt)
                .update());
    }
}
