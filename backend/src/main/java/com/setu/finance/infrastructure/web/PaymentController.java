package com.setu.finance.infrastructure.web;

import com.setu.finance.application.PaymentProviderService;
import com.setu.finance.application.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Day 12 §Finance: payments — see PaymentService's javadoc. */
@RestController
@RequestMapping("/finance/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentProviderService providerService;

    public PaymentController(PaymentService paymentService, PaymentProviderService providerService) {
        this.paymentService = paymentService;
        this.providerService = providerService;
    }

    /** {@code chargeId} is the fee being paid; with it, amount and currency may be left out (they are the fee's) and a different amount is refused. */
    public record InitiateRequest(UUID studentId, UUID chargeId, BigDecimal amount, String currency, String method) {
    }

    public record InitiateResponse(UUID paymentId, boolean replayed) {
    }

    @PostMapping
    public ResponseEntity<?> initiate(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody InitiateRequest request) {
        var result = paymentService.initiatePayment(request.studentId(), request.chargeId(), request.amount(), request.currency(),
            request.method(), idempotencyKey);
        return switch (result) {
            case PaymentService.InitiateResult.Success r ->
                ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(new InitiateResponse(r.paymentId(), r.replayed()));
            case PaymentService.InitiateResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case PaymentService.InitiateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case PaymentService.InitiateResult.IdempotencyKeyConflict ignored -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("this Idempotency-Key was already used for a different request"));
            case PaymentService.InitiateResult.StillProcessing ignored -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("a request with this Idempotency-Key is still being processed; retry shortly"));
            case PaymentService.InitiateResult.ChargeNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("fee not found"));
            case PaymentService.InitiateResult.ChargeNotPayable np ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this fee cannot be paid: it is " + np.status()));
        };
    }

    public record PaymentStatusResponse(UUID id, UUID chargeId, BigDecimal amount, String currency, String status, boolean checkoutStarted) {
    }

    /** One payment's status, to its student or a finance administrator: the page polls this to learn what the provider has confirmed. */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> get(@PathVariable UUID paymentId) {
        return paymentService.get(paymentId)
            .map(p -> ResponseEntity.ok(new PaymentStatusResponse(p.id(), p.studentChargeId(), p.amount(), p.currency(), p.status().name(),
                p.providerRef() != null)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CheckoutResponse(UUID paymentId, String keyId, String orderId, long amount, String currency, String description) {
    }

    /** Opens the provider's order for this payment and returns what the browser needs to show the provider's own checkout. */
    @PostMapping("/{paymentId}/checkout")
    public ResponseEntity<?> checkout(@PathVariable UUID paymentId) {
        return switch (providerService.checkout(paymentId)) {
            case PaymentProviderService.CheckoutResult.Ready r -> ResponseEntity.ok(new CheckoutResponse(r.checkout().paymentId(), r.checkout().keyId(),
                r.checkout().orderId(), r.checkout().amountMinor(), r.checkout().currency(), r.checkout().description()));
            case PaymentProviderService.CheckoutResult.NotFound ignored -> ResponseEntity.notFound().build();
            case PaymentProviderService.CheckoutResult.NotPayable np ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this payment is " + np.status()));
            case PaymentProviderService.CheckoutResult.Unavailable u ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(u.reason()));
        };
    }

    public record ReconcileResponse(String status, boolean changed) {
    }

    /**
     * Asks the provider, server to server, what became of this payment's order, and applies the answer. The browser calls it when its checkout closes
     * with "paid", but it only prompts the lookup: nothing the browser says is believed.
     */
    @PostMapping("/{paymentId}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable UUID paymentId) {
        return switch (providerService.reconcile(paymentId)) {
            case PaymentProviderService.ReconcileResult.Status s -> ResponseEntity.ok(new ReconcileResponse(s.status().name(), s.changed()));
            case PaymentProviderService.ReconcileResult.NotFound ignored -> ResponseEntity.notFound().build();
            case PaymentProviderService.ReconcileResult.NoProviderOrder ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("checkout was not started for this payment"));
            case PaymentProviderService.ReconcileResult.Unavailable u ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(u.reason()));
        };
    }

    public record PaymentResponse(UUID id, UUID tenantId, BigDecimal amount, String currency, String method, String status) {
    }

    /** Admin-only (MANAGE_FINANCE) — see PaymentService#listForStudent's javadoc. */
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> listForStudent(@RequestParam UUID tenantId, @RequestParam UUID studentId) {
        return ResponseEntity.ok(paymentService.listForStudent(tenantId, studentId).stream()
            .map(p -> new PaymentResponse(p.id(), p.tenantId(), p.amount(), p.currency(), p.method(), p.status().name()))
            .toList());
    }

    public record WebhookRequest(String providerEventId, String eventType, String payloadJson) {
    }

    /** Stands in for a real payment-provider webhook — see PaymentService's class javadoc for why no real gateway integration exists in this engagement's scope. */
    @PostMapping("/{paymentId}/events")
    public ResponseEntity<?> recordEvent(@PathVariable UUID paymentId, @RequestBody WebhookRequest request) {
        var result = paymentService.recordWebhookEvent(paymentId, request.providerEventId(), request.eventType(), request.payloadJson());
        return switch (result) {
            case PaymentService.WebhookResult.Success r -> ResponseEntity.ok(new StatusResponse(r.newStatus().name()));
            case PaymentService.WebhookResult.AlreadyProcessed ignored -> ResponseEntity.ok(new StatusResponse("ALREADY_PROCESSED"));
            case PaymentService.WebhookResult.PaymentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("payment not found"));
            case PaymentService.WebhookResult.InvalidEventType invalid ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("cannot apply event type " + invalid.eventType()));
        };
    }

    public record StatusResponse(String status) {
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<?> refund(@PathVariable UUID paymentId) {
        return switch (paymentService.refund(paymentId)) {
            case PaymentService.RefundResult.Success ignored -> ResponseEntity.noContent().build();
            case PaymentService.RefundResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case PaymentService.RefundResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
            case PaymentService.RefundResult.ProviderFailed f ->
                ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("the payment provider did not refund: " + f.message()));
        };
    }

    public record ErrorResponse(String message) {
    }
}
