package com.minhoo.paymentsettlementcore.reconciliation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** 대사 판정 규칙만 검증한다. 스프링·DB 없이 즉시 실행된다. */
class ReconciliationClassifierTest {

    private fun row(amount: String, count: Int, settled: String) =
        ReconciliationRow(paymentId = 1L, paymentAmount = BigDecimal(amount), entryCount = count, settledAmount = BigDecimal(settled))

    @Test
    @DisplayName("정산 1건, 금액 일치 → 불일치 아님")
    fun matched() {
        assertThat(ReconciliationClassifier.classify(row("10000", 1, "10000"))).isNull()
    }

    @Test
    @DisplayName("스케일만 다른 같은 금액(10000 vs 10000.00)은 일치로 본다")
    fun sameAmountDifferentScale() {
        assertThat(BigDecimal("10000") == BigDecimal("10000.00")).isFalse()   // equals는 스케일까지 비교
        assertThat(ReconciliationClassifier.classify(row("10000.00", 1, "10000"))).isNull()
    }

    @Test
    @DisplayName("정산 원장에 없으면 누락 → 이벤트 재처리 대상")
    fun missing() {
        val type = ReconciliationClassifier.classify(row("10000", 0, "0"))
        assertThat(type).isEqualTo(MismatchType.MISSING_SETTLEMENT)
        assertThat(type!!.actionRequired).isEqualTo(ActionRequired.RETRY_EVENT)
    }

    @Test
    @DisplayName("정산이 2건 이상이면 중복 → 사람이 확인 (금액 합계보다 우선)")
    fun duplicate() {
        val type = ReconciliationClassifier.classify(row("10000", 2, "20000"))
        assertThat(type).isEqualTo(MismatchType.DUPLICATE_SETTLEMENT)
        assertThat(type!!.actionRequired).isEqualTo(ActionRequired.MANUAL_REVIEW)
    }

    @Test
    @DisplayName("정산 1건이지만 금액이 다르면 금액 불일치 → 사람이 확인")
    fun amountMismatch() {
        assertThat(ReconciliationClassifier.classify(row("10000", 1, "9000")))
            .isEqualTo(MismatchType.AMOUNT_MISMATCH)
    }

    @Test
    @DisplayName("불일치면 대상 날짜와 함께 결과로 변환, 일치면 null")
    fun toMismatch() {
        val date = LocalDate.of(2026, 1, 15)
        assertThat(ReconciliationClassifier.toMismatch(date, row("10000", 1, "10000"))).isNull()

        val result = ReconciliationClassifier.toMismatch(date, row("10000", 0, "0"))
        assertThat(result).isNotNull
        assertThat(result!!.targetDate).isEqualTo(date)
        assertThat(result.type).isEqualTo(MismatchType.MISSING_SETTLEMENT)
    }
}
