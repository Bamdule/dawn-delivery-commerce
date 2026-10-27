package io.github.bamdule.dawn.fulfillment.job;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 같은 마감 시각 파라미터의 잡은 Spring Batch 가 한 번만 완료시킨다. 실패한 잡에 같은 파라미터로 다시 부르면 실패한 스텝부터 재시작한다
@Component
public class ShipmentJobRunner {

    private final JobOperator jobOperator;
    private final Job dawnShipmentJob;
    private final LocalTime cutoffTime;

    public ShipmentJobRunner(JobOperator jobOperator, Job dawnShipmentJob,
                             @Value("${dawn.fulfillment.cutoff-time:23:00}") LocalTime cutoffTime) {
        this.jobOperator = jobOperator;
        this.dawnShipmentJob = dawnShipmentJob;
        this.cutoffTime = cutoffTime;
    }

    public JobExecution run(LocalDateTime cutoffAt) throws Exception {
        return jobOperator.start(dawnShipmentJob,
                new JobParametersBuilder().addLocalDateTime(ShipmentJobConfig.CUTOFF_AT, cutoffAt).toJobParameters());
    }

    public LocalDateTime cutoffOf(LocalDate date) {
        return date.atTime(cutoffTime);
    }
}
