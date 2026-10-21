package com.minhoo.paymentsettlementcore.account;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(Long accountId, BigDecimal balance, BigDecimal amount) {
        super("잔액 부족 - accountId=%d, balance=%s, amount=%s".formatted(accountId, balance, amount));
    }

    public InsufficientBalanceException(Long accountId, BigDecimal amount) {
        super("잔액 부족 - accountId=%d, amount=%s".formatted(accountId, amount));
    }
}
