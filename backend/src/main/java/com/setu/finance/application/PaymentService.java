package com.setu.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.directory.application.StudentProfileService;
import com.setu.finance.domain.Payment;
import com.setu.finance.domain.PaymentEvent;
import com.setu.finance.domain.PaymentStatus;
import com.setu.finance.domain.StudentCharge;
import com.setu.finance.domain.StudentChargeStatus;
import com.setu.finance.infrastructure.PaymentEventRepository;
import com.setu.finance.infrastructure.PaymentRepository;
import com.setu.finance.infrastructure.StudentAccountRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.IdempotencyGuard;
import com.setu.shared.governance.IdempotencyKey;
import com.setu.shared.governance.IdempotencyKeyRepository;
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
 * SUCCEEDED/FAILED by itself). A payment only ever moves to SUCCEEDED or
 * FAILED through a {@link PaymentEvent} row — a client-reported status is
 * never trusted — by one of two doors:
 * <ul>
 *   <li>the real payment provider, through {@link PaymentProviderService}: its signed webhook, or a server-to-server lookup authenticated with our
 *       secret. This is the authoritative path.</li>
 *   <li>{@link #recordWebhookEvent}, the administrator's stand-in, gated behind {@code MANAGE_FINANCE}: for a college that records payments taken
 *       outside the provider (cash at the counter), for the seed data, and as the way tests drive the state machine without a provider.</li>
 * </ul>
 * Both doors end in {@link PaymentSettlement}, so they do exactly the same thing to the balance, the receipt, the fee and the outbox.
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class PaymentService {

    private static final String INITIATE_PERMISSION = "INITIATE_PAYMENT";
    private static final String MANAGE_PERMISSION = "MANAGE_FINANCE";
    private static final String VIEW_PERMISSION_STUDENT = "VIEW_FINANCE";
    private static final String IDEMPOTENCY_SCOPE_PREFIX = "finance.payment.initiate:";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final StudentAccountRepository studentAccountRepository;
    private final StudentAccountService studentAccountService;
    private final StudentChargeService studentChargeService;
    private final StudentProfileService studentProfileService;
    private final IdempotencyGuard idempotencyGuard;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final PaymentSettlement settlement;
    private final PaymentGateway gateway;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository, PaymentEventRepository paymentEventRepository,
                           StudentAccountRepository studentAccountRepository, StudentAccountService studentAccountService,
                           StudentChargeService studentChargeService, StudentProfileService studentProfileService,
                           IdempotencyGuard idempotencyGuard, IdempotencyKeyRepository idempotencyKeyRepository,
                           AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter, PaymentSettlement settlement,
                           PaymentGateway gateway, ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.studentAccountRepository = studentAccountRepository;
        this.studentAccountService = studentAccountService;
        this.studentChargeService = studentChargeService;
        this.studentProfileService = studentProfileService;
        this.idempotencyGuard = idempotencyGuard;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.settlement = settlement;
        this.gateway = gateway;
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

        /** The fee does not exist, or is not this student's. */
        record ChargeNotFound() implements InitiateResult {
        }

        /** The fee is already paid or waived, so there is nothing to pay. */
        record ChargeNotPayable(String status) implements InitiateResult {
        }
    }

    public record PaymentInitiationResponse(UUID paymentId) {
    }

    @Transactional
    public InitiateResult initiatePayment(UUID studentId, BigDecimal amount, String currency, String method, String idempotencyKey) {
        return initiatePayment(studentId, null, amount, currency, method, idempotencyKey);
    }

    /**
     * @param chargeId the fee being paid, or null for an open-ended payment on the account. With a fee, the amount and currency are the FEE's, read
     *                 here and never taken from the client (a client amount that disagrees is refused), and a second request for a fee that already
     *                 has a payment in flight gets that payment back instead of a second one.
     */
    @Transactional
    public InitiateResult initiatePayment(UUID studentId, UUID chargeId, BigDecimal amount, String currency, String method, String idempotencyKey) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new InitiateResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        authorizationContext.assertCan(INITIATE_PERMISSION,
            ResourceRef.of("payment", UUID.randomUUID(), tenantId).ownedBy(studentInfo.get().ownerUserId()));

        if (chargeId != null) {
            StudentCharge charge = studentChargeService.findById(chargeId).orElse(null);
            if (charge == null || !charge.studentId().equals(studentId) || !charge.tenantId().equals(tenantId)) {
                return new InitiateResult.ChargeNotFound();
            }
            if (charge.status() != StudentChargeStatus.PENDING && charge.status() != StudentChargeStatus.OVERDUE) {
                return new InitiateResult.ChargeNotPayable(charge.status().name());
            }
            if (amount != null && amount.compareTo(charge.amount()) != 0 || currency != null && !currency.equals(charge.currency())) {
                return new InitiateResult.InvalidData("the amount does not match the fee");
            }
            amount = charge.amount();
            currency = charge.currency();
            Optional<Payment> open = paymentRepository.findOpenForCharge(chargeId);
            if (open.isPresent()) {
                return open.get().status() == PaymentStatus.INITIATED
                    ? new InitiateResult.Success(open.get().id(), true)
                    : new InitiateResult.ChargeNotPayable("PAID");
            }
        }

        String scope = IDEMPOTENCY_SCOPE_PREFIX + tenantId;
        String fingerprint = fingerprint(studentId, chargeId, amount, currency, method);

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
            payment = new Payment(UUID.randomUUID(), tenantId, account.id(), amount, currency, method, chargeId);
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

    /**
     * One payment, to the student who owns it or to a finance administrator. This is how the page learns what the provider has told us: the
     * status here is the server's, set only by a provider confirmation, never by anything the browser reports.
     */
    @Transactional(readOnly = true)
    public Optional<Payment> get(UUID paymentId) {
        Optional<Payment> payment = paymentRepository.findById(paymentId);
        payment.ifPresent(p -> assertOwnerOrAdmin(p, false));
        return payment;
    }

    /** Package-private: used by {@link PaymentProviderService} for the checkout and the lookup, which a student may run on their own payment. */
    void assertOwnerOrAdmin(Payment payment, boolean forInitiation) {
        if (authorizationContext.can(MANAGE_PERMISSION, ResourceRef.of("payment", payment.id(), payment.tenantId())).isAllowed()) {
            return;
        }
        UUID studentId = studentAccountRepository.findById(payment.studentAccountId()).map(com.setu.finance.domain.StudentAccount::studentId).orElse(null);
        UUID ownerUserId = studentId == null ? null
            : studentProfileService.findTenantAndOwnerUserId(studentId).map(StudentProfileService.StudentTenantOwner::ownerUserId).orElse(null);
        authorizationContext.assertCan(forInitiation ? INITIATE_PERMISSION : VIEW_PERMISSION_STUDENT,
            ResourceRef.of("payment", payment.id(), payment.tenantId()).ownedBy(ownerUserId));
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
     * Admin-facing lookup so a College Admin (the only role that holds {@code MANAGE_FINANCE} besides Super
     * Admin) can find a student's payments to call {@link #recordWebhookEvent} against — the endpoint
     * itself has no search surface of its own.
     */
    @Transactional(readOnly = true)
    public java.util.List<Payment> listForStudent(UUID tenantId, UUID studentId) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("payment", UUID.randomUUID(), tenantId));
        return studentAccountService.get(tenantId, studentId)
            .map(account -> paymentRepository.findByStudentAccountId(account.id()))
            .orElse(java.util.List.of());
    }

    /**
     * The administrator's stand-in for a provider event (see the class javadoc). Not caught-and-continued on a duplicate {@code provider_event_id}:
     * the fast path is a plain pre-check SELECT (no conflict risk); a genuinely concurrent duplicate delivery is left to propagate uncaught on the
     * rare unique-constraint race, triggering a normal, clean transactional rollback (never {@code UnexpectedRollbackException} — see {@code
     * IdempotencyGuard}'s javadoc for why catching this kind of exception in the SAME transaction is what's unsafe, not letting it propagate).
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
        if (target == null || !payment.canTransitionTo(target)) {
            return new WebhookResult.InvalidEventType(eventType);
        }

        paymentEventRepository.saveAndFlush(new PaymentEvent(UUID.randomUUID(), paymentId, providerEventId, eventType, payloadJson, Instant.now()));

        UUID actor = authorizationContext.currentUserId();
        boolean applied = target == PaymentStatus.SUCCEEDED ? settlement.succeed(payment, actor, null) : settlement.fail(payment, actor, eventType);
        return applied ? new WebhookResult.Success(target) : new WebhookResult.InvalidEventType(eventType);
    }

    public sealed interface RefundResult {
        record Success() implements RefundResult {
        }

        record NotFound() implements RefundResult {
        }

        record InvalidTransition() implements RefundResult {
        }

        /** The provider would not (or could not) refund, so nothing was changed here: the money and the record still agree. */
        record ProviderFailed(String message) implements RefundResult {
        }
    }

    /**
     * Refunds a succeeded payment. A payment the provider took is refunded THROUGH the provider first; only when it has done so (or already had) does
     * the record change, so a provider failure leaves the record untouched instead of showing money returned that was not.
     */
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
        if (payment.providerPaymentId() != null) {
            try {
                gateway.refundInFull(payment.providerPaymentId(), PaymentProviderService.minorUnits(payment.amount(), payment.currency()), "RF-" + payment.id());
            } catch (PaymentGateway.ProviderException e) {
                return new RefundResult.ProviderFailed(e.getMessage());
            }
        }
        return settlement.refund(payment, authorizationContext.currentUserId()) ? new RefundResult.Success() : new RefundResult.InvalidTransition();
    }

    private String fingerprint(UUID studentId, UUID chargeId, BigDecimal amount, String currency, String method) {
        String canonical = studentId + "|" + amount.stripTrailingZeros().toPlainString() + "|" + currency + "|" + method
            + (chargeId == null ? "" : "|" + chargeId);
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
