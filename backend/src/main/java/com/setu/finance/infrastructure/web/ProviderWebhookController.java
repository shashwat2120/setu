package com.setu.finance.infrastructure.web;

import com.setu.finance.application.PaymentProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment provider's webhook. It is reachable without a user token (the provider has none), so it trusts nothing about the caller:
 * {@link PaymentProviderService#handleSignedWebhook} verifies the HMAC signature over the raw body before anything is read, and answers 401 for a body
 * that is not the provider's. Everything the provider might send that this system does not act on is answered 200, so the provider stops retrying it.
 *
 * <p>The body is taken as raw bytes on purpose: the signature is over those exact bytes, and parsing then re-serialising would not reproduce them.
 */
@RestController
@RequestMapping("/finance/provider-webhooks")
public class ProviderWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookController.class);

    private final PaymentProviderService providerService;

    public ProviderWebhookController(PaymentProviderService providerService) {
        this.providerService = providerService;
    }

    public record Ack(String result, String detail) {
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Ack> razorpay(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        PaymentProviderService.Outcome outcome;
        try {
            outcome = providerService.handleSignedWebhook(rawBody, signature, eventId);
        } catch (DataIntegrityViolationException e) {
            // Two deliveries of the same event raced and the other won the unique provider_event_id; the event is processed.
            return ResponseEntity.ok(new Ack("DUPLICATE", "already processed"));
        }
        return switch (outcome) {
            case PaymentProviderService.Outcome.Applied a -> ResponseEntity.ok(new Ack("APPLIED", a.status().name()));
            case PaymentProviderService.Outcome.Duplicate ignored -> ResponseEntity.ok(new Ack("DUPLICATE", "already processed"));
            case PaymentProviderService.Outcome.Ignored i -> ResponseEntity.ok(new Ack("IGNORED", i.reason()));
            case PaymentProviderService.Outcome.BadSignature ignored -> {
                log.warn("a provider webhook with an invalid signature was refused");
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Ack("REFUSED", "invalid signature"));
            }
            case PaymentProviderService.Outcome.NotConfigured ignored ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Ack("REFUSED", "webhook secret is not configured"));
            case PaymentProviderService.Outcome.Malformed m -> ResponseEntity.badRequest().body(new Ack("REFUSED", m.reason()));
        };
    }
}
