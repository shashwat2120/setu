package com.setu.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.finance.domain.Payment;
import com.setu.finance.domain.PaymentEvent;
import com.setu.finance.domain.PaymentStatus;
import com.setu.finance.infrastructure.PaymentEventRepository;
import com.setu.finance.infrastructure.PaymentRepository;
import com.setu.finance.infrastructure.razorpay.RazorpaySignature;
import com.setu.shared.governance.AuditLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The real payment provider's side of a payment (Razorpay today). Three things happen here, and in none of them does a card number come near this
 * system: the card goes from the student's browser straight to the provider.
 * <ol>
 *   <li>{@link #checkout}: the server asks the provider for an ORDER for exactly the payment's amount, and hands the browser the order id and the
 *       provider's public key. The browser cannot choose the amount: it is the order's.</li>
 *   <li>{@link #handleSignedWebhook}: the provider reports what happened. The body is verified against its HMAC signature BEFORE it is read; an event
 *       is deduplicated by the provider's event id; the paid amount and currency must equal the payment's. This is the authoritative path
 *       (canonical spec §5: the webhook decides, the browser never does).</li>
 *   <li>{@link #reconcile}: the same question asked the other way, for when a webhook has not arrived (it cannot reach a machine without a public
 *       address, or it is late): the server asks the provider, authenticated with our secret, what became of the order, and applies the answer
 *       through the same path. The browser's "payment done" callback only prompts this lookup; it is never believed.</li>
 * </ol>
 */
@Service
public class PaymentProviderService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderService.class);

    /** Currencies whose minor unit is not 1/100. Their amounts would need other arithmetic than the one below, so they are refused rather than guessed. */
    private static final Set<String> NOT_TWO_DECIMALS = Set.of("BHD", "CLP", "DJF", "GNF", "IQD", "ISK", "JOD", "JPY", "KMF", "KRW", "KWD", "LYD", "OMR",
        "PYG", "RWF", "TND", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentSettlement settlement;
    private final PaymentService paymentService;
    private final PaymentGateway gateway;
    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public PaymentProviderService(PaymentRepository paymentRepository, PaymentEventRepository paymentEventRepository, PaymentSettlement settlement,
                                  PaymentService paymentService, PaymentGateway gateway, AuditLogWriter auditLogWriter, ObjectMapper objectMapper,
                                  @Value("${app.finance.razorpay.webhook-secret:}") String webhookSecret) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.settlement = settlement;
        this.paymentService = paymentService;
        this.gateway = gateway;
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }

    /** The provider's minor unit (paise for INR) for a Finance amount; refuses an amount with sub-minor digits or a currency that is not two-decimal. */
    static long minorUnits(BigDecimal amount, String currency) {
        if (currency == null || NOT_TWO_DECIMALS.contains(currency.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("currency " + currency + " is not supported for online payment");
        }
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount " + amount + " has digits below the currency's minor unit");
        }
    }

    // ----- checkout -------------------------------------------------------------------------------------------------------------------------

    /** What the browser needs to open the provider's checkout. No secret: {@code keyId} is the public key. */
    public record Checkout(UUID paymentId, String keyId, String orderId, long amountMinor, String currency, String description) {
    }

    public sealed interface CheckoutResult {
        record Ready(Checkout checkout) implements CheckoutResult {
        }

        record NotFound() implements CheckoutResult {
        }

        /** The payment is no longer INITIATED (already paid, failed or refunded). */
        record NotPayable(String status) implements CheckoutResult {
        }

        /** No provider is configured, it refused, or it cannot be reached; nothing was changed. */
        record Unavailable(String reason) implements CheckoutResult {
        }
    }

    @Transactional
    public CheckoutResult checkout(UUID paymentId) {
        Optional<Payment> maybe = paymentRepository.findById(paymentId);
        if (maybe.isEmpty()) {
            return new CheckoutResult.NotFound();
        }
        Payment payment = maybe.get();
        paymentService.assertOwnerOrAdmin(payment, true);
        if (payment.status() != PaymentStatus.INITIATED) {
            return new CheckoutResult.NotPayable(payment.status().name());
        }
        if (!gateway.enabled()) {
            return new CheckoutResult.Unavailable("online payment is not enabled for this college");
        }
        long amountMinor;
        try {
            amountMinor = minorUnits(payment.amount(), payment.currency());
        } catch (IllegalArgumentException e) {
            return new CheckoutResult.Unavailable(e.getMessage());
        }
        String orderId = payment.providerRef();
        if (orderId == null) {
            try {
                orderId = gateway.createOrder(amountMinor, payment.currency(), payment.id().toString(),
                    Map.of("paymentId", payment.id().toString(), "tenantId", payment.tenantId().toString())).id();
            } catch (PaymentGateway.ProviderException e) {
                log.warn("creating a provider order for payment {} failed: {}", payment.id(), e.getMessage());
                return new CheckoutResult.Unavailable("the payment provider could not be reached; try again shortly");
            }
            if (paymentRepository.setProviderRefIfAbsent(payment.id(), orderId) == 0) {
                // A second checkout for the same payment won the race: use its order (ours stays unpaid and harmless at the provider).
                orderId = paymentRepository.findById(payment.id()).map(Payment::providerRef).orElse(orderId);
            }
        }
        return new CheckoutResult.Ready(new Checkout(payment.id(), gateway.publicKeyId(), orderId, amountMinor, payment.currency(), "Fee payment"));
    }

    // ----- the provider's answer: lookup ---------------------------------------------------------------------------------------------------

    public sealed interface ReconcileResult {
        /** The payment is in this state now (it may have just changed, or not). */
        record Status(PaymentStatus status, boolean changed) implements ReconcileResult {
        }

        record NotFound() implements ReconcileResult {
        }

        /** Checkout was never started, so there is no order to ask about. */
        record NoProviderOrder() implements ReconcileResult {
        }

        record Unavailable(String reason) implements ReconcileResult {
        }
    }

    @Transactional
    public ReconcileResult reconcile(UUID paymentId) {
        Optional<Payment> maybe = paymentRepository.findById(paymentId);
        if (maybe.isEmpty()) {
            return new ReconcileResult.NotFound();
        }
        Payment payment = maybe.get();
        paymentService.assertOwnerOrAdmin(payment, true);
        if (payment.status() != PaymentStatus.INITIATED) {
            return new ReconcileResult.Status(payment.status(), false);
        }
        if (payment.providerRef() == null) {
            return new ReconcileResult.NoProviderOrder();
        }
        java.util.List<PaymentGateway.ProviderPayment> attempts;
        try {
            attempts = gateway.paymentsForOrder(payment.providerRef());
        } catch (PaymentGateway.ProviderException e) {
            log.warn("looking up provider order for payment {} failed: {}", payment.id(), e.getMessage());
            return new ReconcileResult.Unavailable("the payment provider could not be reached; try again shortly");
        }
        for (PaymentGateway.ProviderPayment attempt : attempts) {
            if (attempt.captured()) {
                Outcome outcome = applyCapture(payment, attempt.id(), attempt.amountMinor(), attempt.currency(), "reconcile:" + attempt.id(), "PAYMENT_CAPTURED");
                if (outcome instanceof Outcome.Applied) {
                    return new ReconcileResult.Status(PaymentStatus.SUCCEEDED, true);
                }
            }
        }
        return new ReconcileResult.Status(payment.status(), false);
    }

    // ----- the provider's answer: webhook ---------------------------------------------------------------------------------------------------

    public sealed interface Outcome {
        record Applied(PaymentStatus status) implements Outcome {
        }

        /** This event id was already processed. */
        record Duplicate() implements Outcome {
        }

        /** Nothing to do, and the provider must not be told to retry (an event we do not use, an order that is not ours, a payment already settled). */
        record Ignored(String reason) implements Outcome {
        }

        /** The signature did not verify: the body is not the provider's. Nothing was read or changed. */
        record BadSignature() implements Outcome {
        }

        /** No webhook secret is configured, so nothing can be verified and nothing is accepted. */
        record NotConfigured() implements Outcome {
        }

        record Malformed(String reason) implements Outcome {
        }
    }

    /**
     * @param rawBody   the request body exactly as received (the signature is over its bytes)
     * @param signature the {@code X-Razorpay-Signature} header
     * @param eventId   the {@code X-Razorpay-Event-Id} header: the provider's id of this delivery, the same on each retry of it
     */
    @Transactional
    public Outcome handleSignedWebhook(byte[] rawBody, String signature, String eventId) {
        if (webhookSecret.isBlank()) {
            return new Outcome.NotConfigured();
        }
        if (!RazorpaySignature.verify(rawBody, signature, webhookSecret)) {
            return new Outcome.BadSignature();
        }
        if (eventId == null || eventId.isBlank() || eventId.length() > 200) {
            return new Outcome.Malformed("no event id");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            return new Outcome.Malformed("not JSON");
        }
        if (paymentEventRepository.findByProviderEventId(eventId).isPresent()) {
            return new Outcome.Duplicate();
        }
        String name = root.path("event").asText("");
        JsonNode entities = root.path("payload");
        return switch (name) {
            case "payment.captured", "order.paid" -> {
                JsonNode pay = entities.path("payment").path("entity");
                Payment payment = paymentRepository.findByProviderRef(pay.path("order_id").asText("")).orElse(null);
                yield payment == null ? new Outcome.Ignored("not an order of this system")
                    : applyCapture(payment, pay.path("id").asText(""), pay.path("amount").asLong(-1), pay.path("currency").asText(""), eventId, name);
            }
            case "payment.failed" -> {
                // An attempt failed; the ORDER stays open and the student can try again in the same checkout, so our payment does NOT fail: a failed
                // payment is terminal here, and a later successful attempt on the same order would then have nowhere to land. The attempt is recorded.
                JsonNode pay = entities.path("payment").path("entity");
                Payment payment = paymentRepository.findByProviderRef(pay.path("order_id").asText("")).orElse(null);
                if (payment == null) {
                    yield new Outcome.Ignored("not an order of this system");
                }
                paymentEventRepository.saveAndFlush(new PaymentEvent(UUID.randomUUID(), payment.id(), eventId, "PAYMENT_ATTEMPT_FAILED",
                    minimal(name, pay), Instant.now()));
                yield new Outcome.Ignored("a failed attempt does not fail the payment: the order stays open");
            }
            case "refund.processed" -> {
                JsonNode refund = entities.path("refund").path("entity");
                Payment payment = paymentRepository.findByProviderPaymentId(refund.path("payment_id").asText("")).orElse(null);
                if (payment == null) {
                    yield new Outcome.Ignored("not a payment of this system");
                }
                yield applyRefund(payment, eventId, refund.path("amount").asLong(-1), name, refund);
            }
            default -> new Outcome.Ignored("event not used: " + name);
        };
    }

    private Outcome applyCapture(Payment payment, String providerPaymentId, long amountMinor, String currency, String eventId, String eventType) {
        if (providerPaymentId.isBlank()) {
            return new Outcome.Malformed("no payment id");
        }
        long expected;
        try {
            expected = minorUnits(payment.amount(), payment.currency());
        } catch (IllegalArgumentException e) {
            return new Outcome.Ignored(e.getMessage());
        }
        if (amountMinor != expected || !payment.currency().equalsIgnoreCase(currency)) {
            // Money was reported that is not the amount this payment asked for. It is NOT applied; it is made loud for a person to resolve.
            log.error("provider reported {} {} for payment {} which expects {} {}", amountMinor, currency, payment.id(), expected, payment.currency());
            auditLogWriter.write(payment.tenantId(), null, "PAYMENT_PROVIDER_MISMATCH", "finance.payments", payment.id(),
                Map.of("providerPaymentId", providerPaymentId, "reportedMinor", amountMinor, "reportedCurrency", currency, "expectedMinor", expected));
            return new Outcome.Ignored("amount or currency does not match the payment");
        }
        if (!payment.canTransitionTo(PaymentStatus.SUCCEEDED)) {
            return new Outcome.Ignored("payment is already " + payment.status());
        }
        if (paymentEventRepository.findByProviderEventId(eventId).isPresent()) {
            return new Outcome.Duplicate();
        }
        paymentEventRepository.saveAndFlush(new PaymentEvent(UUID.randomUUID(), payment.id(), eventId, "PAYMENT_SUCCEEDED",
            minimal(eventType, providerPaymentId, amountMinor, currency), Instant.now()));
        return settlement.succeed(payment, null, providerPaymentId)
            ? new Outcome.Applied(PaymentStatus.SUCCEEDED) : new Outcome.Ignored("payment was settled concurrently");
    }

    private Outcome applyRefund(Payment payment, String eventId, long amountMinor, String eventType, JsonNode refund) {
        long expected;
        try {
            expected = minorUnits(payment.amount(), payment.currency());
        } catch (IllegalArgumentException e) {
            return new Outcome.Ignored(e.getMessage());
        }
        if (amountMinor != expected) {
            auditLogWriter.write(payment.tenantId(), null, "PAYMENT_PARTIAL_REFUND_NOT_APPLIED", "finance.payments", payment.id(),
                Map.of("refundedMinor", amountMinor, "expectedMinor", expected));
            return new Outcome.Ignored("only a full refund is applied");
        }
        if (!payment.canTransitionTo(PaymentStatus.REFUNDED)) {
            return new Outcome.Ignored("payment is already " + payment.status());
        }
        paymentEventRepository.saveAndFlush(new PaymentEvent(UUID.randomUUID(), payment.id(), eventId, "PAYMENT_REFUNDED",
            minimal(eventType, refund.path("id").asText(""), amountMinor, payment.currency()), Instant.now()));
        return settlement.refund(payment, null)
            ? new Outcome.Applied(PaymentStatus.REFUNDED) : new Outcome.Ignored("payment was settled concurrently");
    }

    /**
     * What is kept of a provider event: its name, the provider's id, the amount and the currency. Not the whole body, which also carries the payer's
     * email, phone number and card network details that Finance has no use for.
     */
    private String minimal(String eventType, String providerId, long amountMinor, String currency) {
        try {
            return objectMapper.writeValueAsString(Map.of("event", eventType, "providerId", providerId, "amountMinor", amountMinor, "currency", currency));
        } catch (IOException e) {
            throw new IllegalStateException("could not serialize the event record", e);
        }
    }

    private String minimal(String eventType, JsonNode payment) {
        return minimal(eventType, payment.path("id").asText(""), payment.path("amount").asLong(-1), payment.path("currency").asText(""));
    }
}
