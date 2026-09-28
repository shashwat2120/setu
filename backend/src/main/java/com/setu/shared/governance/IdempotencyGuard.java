package com.setu.shared.governance;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Claims a client-supplied {@code Idempotency-Key} against {@link
 * IdempotencyKey} (governance.idempotency_keys, V4/V68) — Day 12's first
 * real consumer of this table.
 * <p>
 * <b>The full design</b> (used by {@code finance.application.PaymentService}
 * for payment initiation, the canonical caller): {@link #claim} inserts a
 * PENDING row in its own {@code REQUIRES_NEW} transaction and returns its
 * id, or lets {@code DataIntegrityViolationException} propagate straight
 * out (never caught here — see below) when {@code (key, scope)} already
 * exists. The caller catches that exception in ITS OWN, different
 * transaction and reads the existing row back with a plain {@code
 * findByKeyAndScope} to decide what happened:
 * <ul>
 *   <li>stored {@code requestFingerprint} differs from this request's own —
 *       the same key was reused for a genuinely different request; this is
 *       a conflict, never silently processed as a retry.</li>
 *   <li>fingerprint matches and status is {@code PENDING} — a genuinely
 *       concurrent duplicate arrived before the original finished; the
 *       caller has no result to replay yet (no exactly-once blocking/
 *       polling mechanism is built here — the caller returns a
 *       still-processing response and the client retries shortly).</li>
 *   <li>fingerprint matches and status is {@code COMPLETED} — a legitimate
 *       retry; replay {@code resultJson} verbatim, never re-executing the
 *       financial effect.</li>
 * </ul>
 * Once the real business transaction (payment insert, account balance
 * adjustment, outbox write — all in the caller's own transaction) finishes
 * successfully, the caller calls {@link IdempotencyKeyRepository#completeIfPending}
 * (a plain UPDATE, never an INSERT, so it carries no constraint-violation
 * risk) to store the result for future replays, in the SAME transaction as
 * the business change it accompanies.
 * <p>
 * <b>Why {@link #claim} never catches the constraint violation itself</b>
 * (the same lesson the Sprint 5D transaction-safety hardening established
 * repeatedly): Spring's JPA exception translation marks a transaction
 * rollback-only the MOMENT a {@code DataIntegrityViolationException} is
 * translated, regardless of whether application code catches it
 * afterward — catching it and returning normally from WITHIN the same
 * {@code @Transactional} boundary still throws {@code
 * UnexpectedRollbackException} at that method's own proxy exit. Letting it
 * propagate out of this REQUIRES_NEW method instead causes a clean,
 * isolated rollback of only this inner transaction; the caller's own
 * (different, still-healthy) transaction is what actually catches it.
 */
@Component
public class IdempotencyGuard {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyGuard(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID claim(String key, String scope, String requestFingerprint, Instant expiresAt) {
        IdempotencyKey row = new IdempotencyKey(UUID.randomUUID(), key, scope, Instant.now(), expiresAt, requestFingerprint);
        idempotencyKeyRepository.saveAndFlush(row);
        return row.id();
    }
}
