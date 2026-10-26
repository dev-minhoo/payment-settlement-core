package com.minhoo.paymentsettlementcore.reconciliation

import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import java.time.LocalDateTime
import javax.sql.DataSource

/**
 * 정산 대사 배치: 대상 날짜에 완료된 결제와 정산 원장(settlement_entry)을 맞춰 보고, 불일치를 기록한다.
 *
 * Step 1 (tasklet) 같은 날짜의 이전 대사 결과를 지운다 → 재실행 시 이미 해소된 불일치가 남지 않게
 * Step 2 (chunk)   결제별 정산 집계를 읽고(reader) → 판정(processor, 일치면 null로 걸러냄) → upsert(writer)
 *
 * 멱등성: 결과 테이블은 (target_date, payment_id) 유니크 + upsert.
 * 청크 중간에 실패해 재시작하면, 리더가 마지막 커밋 위치부터 다시 읽고 이미 쓴 행은 upsert로 덮어써 중복이 생기지 않는다.
 *
 * Kotlin 쪽은 Lombok으로 만든 Java 엔티티를 쓰지 않고 JDBC로 직접 읽는다(Kotlin 컴파일러는 Lombok getter를 보지 못함).
 */
@Configuration
class ReconciliationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${psc.reconciliation.chunk-size:100}") private val chunkSize: Int,
) {

    companion object {
        const val JOB_NAME = "paymentReconciliationJob"
        const val CLEAR_STEP = "clearPreviousResultStep"
        const val RECONCILE_STEP = "reconcileStep"
        const val PARAM_TARGET_DATE = "targetDate"

        private const val READ_SQL = """
            SELECT p.id                       AS payment_id,
                   p.amount                   AS payment_amount,
                   COUNT(s.id)                AS entry_count,
                   COALESCE(SUM(s.amount), 0) AS settled_amount
              FROM payment p
              LEFT JOIN settlement_entry s ON s.payment_id = p.id
             WHERE p.status = 'COMPLETED'
               AND p.created_at >= ?
               AND p.created_at < ?
             GROUP BY p.id, p.amount
             ORDER BY p.id
        """

        private const val UPSERT_SQL = """
            INSERT INTO reconciliation_mismatch
                (target_date, payment_id, mismatch_type, action_required,
                 payment_amount, settled_amount, entry_count, detected_at)
            VALUES
                (:targetDate, :paymentId, :mismatchType, :actionRequired,
                 :paymentAmount, :settledAmount, :entryCount, :detectedAt) AS new
            ON DUPLICATE KEY UPDATE
                mismatch_type   = new.mismatch_type,
                action_required = new.action_required,
                payment_amount  = new.payment_amount,
                settled_amount  = new.settled_amount,
                entry_count     = new.entry_count,
                detected_at     = new.detected_at
        """
    }

    @Bean
    fun paymentReconciliationJob(): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .start(clearPreviousResultStep())
            .next(reconcileStep())
            .build()

    @Bean
    fun clearPreviousResultStep(): Step =
        StepBuilder(CLEAR_STEP, jobRepository)
            .tasklet(clearPreviousResultTasklet(null), transactionManager)
            .build()

    @Bean
    @StepScope
    fun clearPreviousResultTasklet(
        @Value("#{jobParameters['targetDate']}") targetDate: LocalDate?,
    ): Tasklet = Tasklet { _, _ ->
        val date = requireNotNull(targetDate) { "jobParameters.targetDate가 필요합니다" }
        jdbcTemplate.update("DELETE FROM reconciliation_mismatch WHERE target_date = ?", date)
        RepeatStatus.FINISHED
    }

    @Bean
    fun reconcileStep(): Step =
        StepBuilder(RECONCILE_STEP, jobRepository)
            .chunk<ReconciliationRow, ReconciliationMismatch>(chunkSize)
            .transactionManager(transactionManager)
            .reader(reconciliationReader(null))
            .processor(reconciliationProcessor(null))
            .writer(reconciliationWriter())
            .build()

    @Bean
    @StepScope
    fun reconciliationReader(
        @Value("#{jobParameters['targetDate']}") targetDate: LocalDate?,
    ): JdbcCursorItemReader<ReconciliationRow> {
        val date = requireNotNull(targetDate) { "jobParameters.targetDate가 필요합니다" }
        val from: LocalDateTime = date.atStartOfDay()
        val to: LocalDateTime = date.plusDays(1).atStartOfDay()

        return JdbcCursorItemReaderBuilder<ReconciliationRow>()
            .name("reconciliationReader")   // 재시작 시 읽은 위치를 ExecutionContext에 이 이름으로 저장
            .dataSource(dataSource)
            .sql(READ_SQL)
            .preparedStatementSetter(PreparedStatementSetter { ps ->
                ps.setObject(1, from)
                ps.setObject(2, to)
            })
            .rowMapper(RowMapper { rs, _ ->
                ReconciliationRow(
                    paymentId = rs.getLong("payment_id"),
                    paymentAmount = rs.getBigDecimal("payment_amount"),
                    entryCount = rs.getInt("entry_count"),
                    settledAmount = rs.getBigDecimal("settled_amount"),
                )
            })
            .build()
    }

    /** 일치하면 null을 돌려 writer로 넘기지 않는다(filter). */
    @Bean
    @StepScope
    fun reconciliationProcessor(
        @Value("#{jobParameters['targetDate']}") targetDate: LocalDate?,
    ): ItemProcessor<ReconciliationRow, ReconciliationMismatch> {
        val date = requireNotNull(targetDate) { "jobParameters.targetDate가 필요합니다" }
        return ItemProcessor { row -> ReconciliationClassifier.toMismatch(date, row) }
    }

    @Bean
    fun reconciliationWriter(): JdbcBatchItemWriter<ReconciliationMismatch> =
        JdbcBatchItemWriterBuilder<ReconciliationMismatch>()
            .dataSource(dataSource)
            .sql(UPSERT_SQL)
            .itemSqlParameterSourceProvider { m ->
                MapSqlParameterSource()
                    .addValue("targetDate", m.targetDate)
                    .addValue("paymentId", m.paymentId)
                    .addValue("mismatchType", m.type.name)
                    .addValue("actionRequired", m.type.actionRequired.name)
                    .addValue("paymentAmount", m.paymentAmount)
                    .addValue("settledAmount", m.settledAmount)
                    .addValue("entryCount", m.entryCount)
                    .addValue("detectedAt", LocalDateTime.now())
            }
            // 같은 값으로 upsert하면 MySQL은 영향 행 0을 돌려준다 → 기본 검사(0행이면 실패)를 끈다
            .assertUpdates(false)
            .build()
}
