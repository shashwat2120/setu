package com.setu.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.directory.application.StudentProfileService;
import com.setu.finance.domain.Payment;
import com.setu.finance.domain.PaymentEvent;
import com.setu.finance.domain.PaymentFailedEvent;
import com.setu.finance.domain.PaymentRefundedEvent;
import com.setu.finance.domain.PaymentStatus;
import com.setu.finance.domain.PaymentSuccessfulEvent;
import com.setu.finance.domain.Receipt;
import com.setu.finance.infrastructure.PaymentEventRepository;
import com.setu.finance.infrastructure.PaymentRepository;
import com.setu.finance.infrastructure.ReceiptRepository;
import com.setu.finance.infrastructure.StudentAccountRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.IdempotencyGuard;
import com.setu.shared.governance.IdempotencyKey;
import com.setu.shared.governance.IdempotencyKeyRepository;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 12 §Finance: payments/payment_events/receipts — state-machines.md #20
 * (Payment) exactly. {@link #initiatePayment} is the student-facing,
 * idempotent-by-client-key entry point (INITIATED row only — never
 * SUCCEEDED/FAILED by itself). {@link #recordWebhookEvent} is the ONLY way
 * a payment ever transitions to SUCCEEDED/FAILED, driven by a {@link
 * PaymentEvent} row — matching the state machine's explicit rule that a
 * client-reported status is never trusted. No real external payment
 * gateway exists in this engagement's scope (Day 12 explicitly forbids
 * inventing one), so this "webhook" endpoint stands in for what a real
 * provider would call — gated behind {@code MANAGE_FINANCE} here rather
 * than left unauthenticated, since there is no real provider signature to
 * verify instead.
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class PaymentService {

    private static final String INITIATE_PERMISSION = "INITIATE_PAYMENT";
    private static final String MANAGE_PERMISSION = "MANAGE_FINANCE";
    private static final String IDEMPOTENCY_SCOPE_PREFIX = "finance.payment.initiate:";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ReceiptRepository receiptRepository;
    private final StudentAccountRepository studentAccountRepository;
    private final StudentAccountService studentAccountService;
    private final StudentProfileService studentProfileService;
    private final IdempotencyGuard idempotencyGuard;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository, PaymentEventRepository paymentEventRepository,
                           ReceiptRepository receiptRepository, StudentAccountRepository studentAccountRepository,
                           StudentAccountService studentAccountService,
                           StudentProfileService studentProfileService, IdempotencyGuard idempotencyGuard,
                           IdempotencyKeyRepository idempotencyKeyRepository, AuthorizationContext authorizationContext,
                           AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter, ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.receiptRepository = receiptRepository;
        this.studentAccountRepository = studentAccountRepository;
        this.studentAccountService = studentAccountService;
        this.studentProfileService = studentProfileService;
        this.idempotencyGuard = idempotencyGuard;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
        this.objectMapper = objectMapper;
    }

    public sealed interface InitiateResult {
        record Success(UUID paymentId, boolean replayed) implements InitiateResult {
        }

        record StudentNotFound() implements InitiateResult {
        }

        record InvalidData(String message) implements InitiateResult {
        }

        /** Same Idempotency-Key reused with a request that doesn't match the original — never silently processed as a retry. */
        record IdempotencyKeyConflict() implements InitiateResult {
        }

        /** A genuinely concurrent duplicate arrived before the original finished — no result to replay yet; retry shortly with the same key. */
        record StillProcessing() implements InitiateResult {
        }
    }

    public record PaymentInitiationResponse(UUID paymentId) {
    }

    @Transactional
    public InitiateResult initiatePayment(UUID studentId, BigDecimal amount, String currency, String method, String idempotencyKey) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new InitiateResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        authorizationContext.assertCan(INITIATE_PERMISSION,
            ResourceRef.of("payment", UUID.randomUUID(), tenantId).ownedBy(studentInfo.get().ownerUserId()));

        String scope = IDEMPOTENCY_SCOPE_PREFIX + tenantId;
        String fingerprint = fingerprint(studentId, amount, currency, method);

        UUID claimedId;
        try {
            claimedId = idempotencyGuard.claim(idempotencyKey, scope, fingerprint, Instant.now().plus(1, ChronoUnit.DAYS));
        } catch (DataIntegrityViolationException e) {
            IdempotencyKey existing = idempotencyKeyRepository.findByKeyAndScope(idempotencyKey, scope).orElseThrow();
            if (!existing.requestFingerprint().equals(fingerprint)) {
                return new InitiateResult.IdempotencyKeyConflict();
            }
            if (existing.status() == IdempotencyKey.Status.PENDING) {
                return new InitiateResult.StillProcessing();
            }
            return replayInitiation(existing.resultJson());
        }

        Payment payment;
        try {
            var account = studentAccountService.getOrCreate(tenantId, studentId, currency);
            payment = new Payment(UUID.randomUUID(), tenantId, account.id(), amount, currency, method);
        } catch (IllegalArgumentException e) {
            return new InitiateResult.InvalidData(e.getMessage());
        }
        paymentRepository.saveAndFlush(payment);
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "PAYMENT_INITIATED",
            "finance.payments", payment.id(), java.util.Map.of("studentId", studentId, "amount", amount));

        String resultJson = writeJson(new PaymentInitiationResponse(payment.id()));
        idempotencyKeyRepository.completeIfPending(claimedId, resultJson);
        return new InitiateResult.Success(payment.id(), false);
    }

    private InitiateResult replayInitiation(String resultJson) {
        try {
            PaymentInitiationResponse response = objectMapper.readValue(resultJson, PaymentInitiationResponse.class);
            return new InitiateResult.Success(response.paymentId(), true);
        } catch (Exception e) {
            throw new IllegalStateException("stored idempotency result is not readable", e);
        }
    }

    public sealed interface WebhookResult {
        record Success(PaymentStatus newStatus) implements WebhookResult {
        }

        record AlreadyProcessed() implements WebhookResult {
        }

        record PaymentNotFound() implements WebhookResult {
        }

        record InvalidEventType(String eventType) implements WebhookResult {
        }
    }

    /**
     * The ONLY path that transitions a payment to SUCCEEDED/FAILED. Not
     * caught-and-continued on a duplicate {@code provider_event_id}: the
     * fast path is a plain pre-check SELECT (no conflict risk); a
     * genuinely concurrent duplicate delivery is left to propagate
     * uncaught on the rare unique-constraint race, triggering a normal,
     * clean transactional rollback (never {@code UnexpectedRollbackException}
     * — see {@code IdempotencyGuard}'s javadoc for why catching this kind
     * of exception in the SAME transaction is what's unsafe, not letting
     * it propagate) — {@code GlobalExceptionHandler}'s existing safety net
     * maps it to a 409 the webhook sender's own standard retry behavior
     * resolves cleanly on the next delivery attempt.
     */
    @Transactional
    public WebhookResult recordWebhookEvent(UUID paymentId, String providerEventId, String eventType, String payloadJson) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("payment", paymentId, null));
        if (paymentEventRepository.findByProviderEventId(providerEventId).isPresent()) {
            return new WebhookResult.AlreadyProcessed();
        }
        Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
        if (maybePayment.isEmpty()) {
            return new WebhookResult.PaymentNotFound();
        }
        Payment payment = maybePayment.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("payment", payment.id(), payment.tenantId()));

        PaymentStatus target = switch (eventType) {
            case "PAYMENT_SUCCEEDED" -> PaymentStatus.SUCCEEDED;
            case "PAYMENT_FAILED" -> PaymentStatus.FAILED;
            default -> null;
        };
        if (target == null) {
            return new WebhookResult.InvalidEventType(eventType);
        }
        if (!payment.canTransitionTo(target)) {
            return new WebhookResult.InvalidEventType(eventType);
        }

        PaymentEvent event = new PaymentEvent(UUID.randomUUID(), paymentId, providerEventId, eventType, payloadJson, Instant.now());
        paymentEventRepository.saveAndFlush(event);

        int updated = paymentRepository.transitionIfStatus(paymentId, payment.status(), target);
        if (updated == 0) {
            return new WebhookResult.InvalidEventType(eventType);
        }

        UUID studentId = studentIdFor(payment);
        UUID correlationId = UUID.randomUUID();
        if (target == PaymentStatus.SUCCEEDED) {
            studentAccountService.adjustBalance(payment.studentAccountId(), payment.amount().negate());
            Receipt receipt = new Receipt(UUID.randomUUID(), payment.tenantId(), payment.id(),
                "RCT-" + payment.id(), Instant.now());
            receiptRepository.saveAndFlush(receipt);
            var domainEvent = new PaymentSuccessfulEvent(UUID.randomUUID(), Instant.now(), payment.tenantId(),
                payment.studentAccountId(), correlationId, null, payment.id(), studentId, payment.amount(), payment.currency());
            outboxEventWriter.write("payments", payment.studentAccountId(), domainEvent.eventType(), domainEvent.eventVersion(),
                domainEvent, payment.tenantId(), correlationId, null);
            auditLogWriter.write(payment.tenantId(), authorizationContext.currentUserId(), "PAYMENT_SUCCEEDED",
                "finance.payments", payment.id(), java.util.Map.of("amount", payment.amount()));
        } else {
            var domainEvent = new PaymentFailedEvent(UUID.randomUUID(), Instant.now(), payment.tenantId(),
                payment.studentAccountId(), correlationId, null, payment.id(), studentId, eventType);
            outboxEventWriter.write("payments", payment.studentAccountId(), domainEvent.eventType(), domainEvent.eventVersion(),
                domainEvent, payment.tenantId(), correlationId, null);
            auditLogWriter.write(payment.tenantId(), authorizationContext.currentUserId(), "PAYMENT_FAILED",
                "finance.payments", payment.id(), java.util.Map.of());
        }
        return new WebhookResult.Success(target);
    }

    public sealed interface RefundResult {
        record Success() implements RefundResult {
        }

        record NotFound() implements RefundResult {
        }

        record InvalidTransition() implements RefundResult {
        }
    }

    @Transactional
    public RefundResult refund(UUID paymentId) {
        Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
        if (maybePayment.isEmpty()) {
            return new RefundResult.NotFound();
        }
        Payment payment = maybePayment.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("payment", payment.id(), payment.tenantId()));
        if (!payment.canTransitionTo(PaymentStatus.REFUNDED)) {
            return new RefundResult.InvalidTransition();
        }
        int updated = paymentRepository.transitionIfStatus(paymentId, payment.status(), PaymentStatus.REFUNDED);
        if (updated == 0) {
            return new RefundResult.InvalidTransition();
        }
        studentAccountService.adjustBalance(payment.studentAccountId(), payment.amount());
        UUID actor = authorizationContext.currentUserId();
        UUID studentId = studentIdFor(payment);
        UUID correlationId = UUID.randomUUID();
        var domainEvent = new PaymentRefundedEvent(UUID.randomUUID(), Instant.now(), payment.tenantId(),
            payment.studentAccountId(), correlationId, null, payment.id(), studentId, payment.amount(), actor);
        outboxEventWriter.write("payments", payment.studentAccountId(), domainEvent.eventType(), domainEvent.eventVersion(),
            domainEvent, payment.tenantId(), correlationId, null);
        auditLogWriter.write(payment.tenantId(), actor, "PAYMENT_REFUNDED", "finance.payments", payment.id(), java.util.Map.of());
        return new RefundResult.Success();
    }

    private UUID studentIdFor(Payment payment) {
        return studentAccountRepository.findById(payment.studentAccountId())
            .map(com.setu.finance.domain.StudentAccount::studentId)
            .orElse(null);
    }

    private String fingerprint(UUID studentId, BigDecimal amount, String currency, String method) {
        String canonical = studentId + "|" + amount.stripTrailingZeros().toPlainString() + "|" + currency + "|" + method;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize idempotency result", e);
        }
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #initiatePayment} — empty if not a Success (e.g. this call site deliberately wants to see failures too in the future; today the seeder always expects success). */
    @Transactional
    public java.util.Optional<UUID> initiatePaymentForSeed(UUID studentId, java.math.BigDecimal amount, String currency,
                                                             String method, String idempotencyKey) {
        return switch (initiatePayment(studentId, amount, currency, method, idempotencyKey)) {
            case InitiateResult.Success s -> java.util.Optional.of(s.paymentId());
            default -> java.util.Optional.empty();
        };
    }

}
