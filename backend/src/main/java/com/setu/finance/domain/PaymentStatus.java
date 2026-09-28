package com.setu.finance.domain;

/** Matches finance.payments.status's DB CHECK constraint and state-machines.md #20 (Payment) exactly. */
public enum PaymentStatus {
    INITIATED, SUCCEEDED, FAILED, REFUNDED
}
