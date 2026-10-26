package com.minhoo.paymentsettlementcore.reconciliation

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 대사 입력 1행: 결제 1건과, 그 결제에 대해 정산 원장에 쌓인 항목의 개수·합계.
 * 리더가 SQL 집계 결과를 이 형태로 넘긴다.
 */
data class ReconciliationRow(
    val paymentId: Long,
    val paymentAmount: BigDecimal,
    val entryCount: Int,
    val settledAmount: BigDecimal,
)

/**
 * 불일치 유형과 후속 조치.
 * 원칙(실무 대사와 동일): 원인이 명확하고 되돌릴 수 있는 것만 자동 처리 대상으로, 돈이 두 번 나갔을 수 있는 것은 사람이 판단.
 */
enum class MismatchType(val actionRequired: ActionRequired) {
    /** 결제는 완료됐는데 정산 원장에 없음 → 이벤트 재처리로 메울 수 있음 */
    MISSING_SETTLEMENT(ActionRequired.RETRY_EVENT),

    /** 같은 결제가 정산 원장에 2건 이상 → 이중 정산 가능성, 사람이 확인 */
    DUPLICATE_SETTLEMENT(ActionRequired.MANUAL_REVIEW),

    /** 정산 금액 합계가 결제 금액과 다름 → 사람이 확인 */
    AMOUNT_MISMATCH(ActionRequired.MANUAL_REVIEW),
}

enum class ActionRequired { RETRY_EVENT, MANUAL_REVIEW }

/** 대사 결과 1건. reconciliation_mismatch 테이블 1행이 된다. */
data class ReconciliationMismatch(
    val targetDate: LocalDate,
    val paymentId: Long,
    val type: MismatchType,
    val paymentAmount: BigDecimal,
    val settledAmount: BigDecimal,
    val entryCount: Int,
)

/**
 * 대사 판정 규칙. 스프링·DB에 의존하지 않는 순수 함수라 단위 테스트로 규칙만 검증할 수 있다.
 */
object ReconciliationClassifier {

    /** @return 불일치 유형, 일치하면 null */
    fun classify(row: ReconciliationRow): MismatchType? = when {
        row.entryCount == 0 -> MismatchType.MISSING_SETTLEMENT
        row.entryCount > 1 -> MismatchType.DUPLICATE_SETTLEMENT
        // BigDecimal은 == (equals)가 스케일까지 비교한다: 10000 != 10000.00
        // 금액 비교는 반드시 compareTo
        row.settledAmount.compareTo(row.paymentAmount) != 0 -> MismatchType.AMOUNT_MISMATCH
        else -> null
    }

    fun toMismatch(targetDate: LocalDate, row: ReconciliationRow): ReconciliationMismatch? =
        classify(row)?.let { type ->
            ReconciliationMismatch(
                targetDate = targetDate,
                paymentId = row.paymentId,
                type = type,
                paymentAmount = row.paymentAmount,
                settledAmount = row.settledAmount,
                entryCount = row.entryCount,
            )
        }
}
