package com.minhoo.paymentsettlementcore.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // ---- 0. 문제 재현용: 읽고 → 계산하고 → 절대값으로 덮어쓰기 ----

    @Query("select a.balance from Account a where a.id = :id")
    BigDecimal findBalance(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("update Account a set a.balance = :balance where a.id = :id")
    int overwriteBalance(@Param("id") Long id, @Param("balance") BigDecimal balance);

    // ---- 1. 조건부 UPDATE: 검사와 차감을 한 문장으로 ----

    /**
     * 잔액이 충분할 때만 차감한다. 반환값(영향 행 수)이 0이면 잔액 부족.
     * bulk update는 @Version을 자동으로 올리지 않으므로 직접 올려 낙관적 락 경로와 섞여도 안전하게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update Account a
               set a.balance = a.balance - :amount,
                   a.version = a.version + 1
             where a.id = :id
               and a.balance >= :amount
            """)
    int withdrawIfEnough(@Param("id") Long id, @Param("amount") BigDecimal amount);

    // ---- 2. 비관적 락: SELECT ... FOR UPDATE ----

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
