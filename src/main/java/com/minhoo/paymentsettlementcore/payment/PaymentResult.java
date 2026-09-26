package com.minhoo.paymentsettlementcore.payment;

public record PaymentResult(Status status, Long paymentId) {

    public enum Status { SUCCESS, DUPLICATED, IN_PROGRESS }

    public static PaymentResult success(Payment p)    { return new PaymentResult(Status.SUCCESS, p.getId()); }
    public static PaymentResult duplicated(Payment p) { return new PaymentResult(Status.DUPLICATED, p.getId()); }
    public static PaymentResult inProgress()          { return new PaymentResult(Status.IN_PROGRESS, null); }
}