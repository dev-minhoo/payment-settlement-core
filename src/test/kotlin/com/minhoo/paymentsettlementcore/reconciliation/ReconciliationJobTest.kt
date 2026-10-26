package com.minhoo.paymentsettlementcore.reconciliation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 실제 MySQL(docker-compose)에 결제·정산 데이터를 넣고 대사 Job을 돌린다.
 * 다른 테스트의 결제와 섞이지 않게 과거 날짜(TARGET_DATE)를 쓴다.
 * 청크 크기를 2로 줄여 여러 청크로 나뉘어 처리되는지도 확인한다.
 */
@SpringBootTest(properties = ["psc.reconciliation.chunk-size=2"])
class ReconciliationJobTest {

    companion object {
        private val TARGET_DATE: LocalDate = LocalDate.of(2026, 1, 15)
    }

    @Autowired private lateinit var jobOperator: JobOperator
    @Autowired private lateinit var paymentReconciliationJob: Job
    @Autowired private lateinit var jdbc: JdbcTemplate

    private var okId = 0L
    private var missingId = 0L
    private var duplicateId = 0L
    private var amountId = 0L

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM reconciliation_mismatch")
        jdbc.update("DELETE FROM settlement_entry")
        jdbc.update("DELETE FROM payment")

        // 대상 날짜의 완료 결제 5건
        okId = payment("10000", TARGET_DATE)
        settle(okId, "10000")

        missingId = payment("20000", TARGET_DATE)                       // 정산 없음

        duplicateId = payment("30000", TARGET_DATE)
        settle(duplicateId, "30000")
        settle(duplicateId, "30000")                                    // 이중 정산

        amountId = payment("40000", TARGET_DATE)
        settle(amountId, "39000")                                       // 금액 다름

        val scaleId = payment("50000.00", TARGET_DATE)
        settle(scaleId, "50000")                                        // 스케일만 다름 → 일치

        // 대상이 아닌 결제: 다른 날짜, 완료되지 않은 결제
        payment("60000", TARGET_DATE.plusDays(1))
        payment("70000", TARGET_DATE, status = "REQUESTED")
    }

    @Test
    @DisplayName("누락·중복·금액 불일치 3건만 기록되고, 일치·대상 외 결제는 기록되지 않는다")
    fun detectsMismatches() {
        val execution = run()

        assertThat(execution.status.name).isEqualTo("COMPLETED")
        assertThat(mismatchTypes()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                missingId to "MISSING_SETTLEMENT",
                duplicateId to "DUPLICATE_SETTLEMENT",
                amountId to "AMOUNT_MISMATCH",
            )
        )
        assertThat(actionOf(missingId)).isEqualTo("RETRY_EVENT")
        assertThat(actionOf(duplicateId)).isEqualTo("MANUAL_REVIEW")
    }

    @Test
    @DisplayName("청크 단위 처리: 5건 읽고 2건 걸러내고 3건 쓰며, 청크 크기 2로 여러 번 커밋된다")
    fun processesInChunks() {
        val execution = run()

        val step = execution.stepExecutions.first { it.stepName == ReconciliationJobConfig.RECONCILE_STEP }
        assertThat(step.readCount).isEqualTo(5L)
        assertThat(step.filterCount).isEqualTo(2L)
        assertThat(step.writeCount).isEqualTo(3L)
        assertThat(step.commitCount).isGreaterThanOrEqualTo(3L)   // 5건 / 청크 2 → 최소 3번 커밋
    }

    @Test
    @DisplayName("같은 날짜를 다시 대사해도 결과가 두 배로 쌓이지 않는다 (멱등)")
    fun rerunIsIdempotent() {
        run()
        run()

        assertThat(mismatchCount()).isEqualTo(3)
    }

    @Test
    @DisplayName("누락을 메운 뒤 다시 대사하면, 해소된 불일치는 결과에서 사라진다")
    fun resolvedMismatchDisappearsOnRerun() {
        run()
        settle(missingId, "20000")

        run()

        assertThat(mismatchTypes()).doesNotContainKey(missingId)
        assertThat(mismatchCount()).isEqualTo(2)
    }

    private fun run(): JobExecution {
        val params = JobParametersBuilder()
            .addLocalDate(ReconciliationJobConfig.PARAM_TARGET_DATE, TARGET_DATE)
            .addString("runId", UUID.randomUUID().toString())   // 같은 날짜 재실행을 별도 JobInstance로
            .toJobParameters()
        return jobOperator.start(paymentReconciliationJob, params)
    }

    private fun payment(amount: String, date: LocalDate, status: String = "COMPLETED"): Long {
        val key = "RECON-" + UUID.randomUUID()
        jdbc.update(
            "INSERT INTO payment (idempotency_key, amount, status, created_at) VALUES (?, ?, ?, ?)",
            key, BigDecimal(amount), status, date.atTime(10, 0),
        )
        return jdbc.queryForObject("SELECT id FROM payment WHERE idempotency_key = ?", Long::class.javaObjectType, key)!!
    }

    private fun settle(paymentId: Long, amount: String) {
        jdbc.update(
            "INSERT INTO settlement_entry (payment_id, amount, source_event_id, created_at) VALUES (?, ?, ?, NOW())",
            paymentId, BigDecimal(amount), UUID.randomUUID().toString(),
        )
    }

    private fun mismatchTypes(): Map<Long, String> =
        jdbc.query("SELECT payment_id, mismatch_type FROM reconciliation_mismatch WHERE target_date = ?",
            RowMapper { rs, _ -> rs.getLong("payment_id") to rs.getString("mismatch_type") }, TARGET_DATE)
            .toMap()

    private fun actionOf(paymentId: Long): String =
        jdbc.queryForObject(
            "SELECT action_required FROM reconciliation_mismatch WHERE target_date = ? AND payment_id = ?",
            String::class.java, TARGET_DATE, paymentId,
        )!!

    private fun mismatchCount(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_mismatch WHERE target_date = ?",
            Int::class.javaObjectType, TARGET_DATE)!!
}
