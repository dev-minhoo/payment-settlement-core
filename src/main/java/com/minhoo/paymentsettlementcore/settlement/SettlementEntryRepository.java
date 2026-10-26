package com.minhoo.paymentsettlementcore.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementEntryRepository extends JpaRepository<SettlementEntry, Long> {

    long countByPaymentId(Long paymentId);
}
