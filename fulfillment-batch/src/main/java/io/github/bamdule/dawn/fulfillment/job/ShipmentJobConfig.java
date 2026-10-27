package io.github.bamdule.dawn.fulfillment.job;

import io.github.bamdule.dawn.fulfillment.allocation.AllocationRunner;
import io.github.bamdule.dawn.fulfillment.shipment.ShipmentCreator;
import io.github.bamdule.dawn.fulfillment.target.TargetFixer;
import io.github.bamdule.dawn.fulfillment.unfillable.UnfillablePublisher;
import io.github.bamdule.dawn.fulfillment.verification.RunVerifier;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;

// 마감 시각이 잡 파라미터다. NOW() 로 대상을 잡으면 늦게 돌 때마다 대상이 달라진다 (docs/design/05 "배치를 만들 때 함께 만들 것").
// 스텝마다 짧은 트랜잭션을 스스로 나눠 쓰므로 스텝 자체는 트랜잭션으로 감싸지 않는다. 감싸면 스텝 전체가 한 트랜잭션이 된다
@Configuration
public class ShipmentJobConfig {

    public static final String JOB_NAME = "dawnShipmentJob";
    public static final String CUTOFF_AT = "cutoffAt";
    private static final String CENTER_ID = "centerId";

    private final ResourcelessTransactionManager stepTransactionManager = new ResourcelessTransactionManager();

    @Bean
    public Job dawnShipmentJob(JobRepository jobRepository, Step targetFixStep, Step allocationStep, Step unfillablePublishStep,
                               Step partitionedShipmentStep, Step verificationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(targetFixStep)
                .next(allocationStep)
                .next(unfillablePublishStep)
                .next(partitionedShipmentStep)
                .next(verificationStep)
                .build();
    }

    @Bean
    public Step targetFixStep(JobRepository jobRepository, TargetFixer targetFixer) {
        return cutoffStep("targetFixStep", jobRepository, targetFixer::fix);
    }

    @Bean
    public Step allocationStep(JobRepository jobRepository, AllocationRunner allocationRunner) {
        return cutoffStep("allocationStep", jobRepository, allocationRunner::allocate);
    }

    @Bean
    public Step unfillablePublishStep(JobRepository jobRepository, UnfillablePublisher unfillablePublisher) {
        return cutoffStep("unfillablePublishStep", jobRepository, unfillablePublisher::publish);
    }

    @Bean
    public Step verificationStep(JobRepository jobRepository, RunVerifier runVerifier) {
        return cutoffStep("verificationStep", jobRepository, runVerifier::verify);
    }

    // 센터마다 파티션 하나다. 주문번호 범위로 나누면 여러 파티션이 같은 센터, 같은 상품 행을 동시에 차감해 락을 기다린다
    @Bean
    public Step partitionedShipmentStep(JobRepository jobRepository, Step shipmentStep, JdbcClient jdbcClient) {
        Partitioner byCenter = gridSize -> {
            Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
            List<String> centerIds = jdbcClient.sql("SELECT center_id FROM center ORDER BY center_id").query(String.class).list();
            for (String centerId : centerIds) {
                ExecutionContext executionContext = new ExecutionContext();
                executionContext.putString(CENTER_ID, centerId);
                partitions.put("center-" + centerId, executionContext);
            }
            return partitions;
        };
        return new StepBuilder("partitionedShipmentStep", jobRepository)
                .partitioner("shipmentStep", byCenter)
                .step(shipmentStep)
                .taskExecutor(new SimpleAsyncTaskExecutor("shipment-"))
                .build();
    }

    @Bean
    public Step shipmentStep(JobRepository jobRepository, ShipmentCreator shipmentCreator) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            String centerId = chunkContext.getStepContext().getStepExecution().getExecutionContext().getString(CENTER_ID);
            shipmentCreator.createForCenter(cutoffAt(chunkContext), centerId);
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("shipmentStep", jobRepository).tasklet(tasklet, stepTransactionManager).build();
    }

    private Step cutoffStep(String name, JobRepository jobRepository, Consumer<LocalDateTime> work) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            work.accept(cutoffAt(chunkContext));
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(name, jobRepository).tasklet(tasklet, stepTransactionManager).build();
    }

    private static LocalDateTime cutoffAt(ChunkContext chunkContext) {
        return chunkContext.getStepContext().getStepExecution().getJobParameters().getLocalDateTime(CUTOFF_AT);
    }
}
