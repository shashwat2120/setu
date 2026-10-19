package com.setu.finance.application;

import java.util.List;
import java.util.Map;

/**
 * What Finance needs from an external payment provider, and nothing more. The provider takes the card (or UPI) details in its own checkout, in the
 * student's browser: no card number, CVV or UPI secret ever reaches this system, so none can be stored (canonical spec §3). This system asks the
 * provider for an order, tells the browser which order to open, and then learns what happened from the provider, never from the browser:
 * the signed webhook, or a server-to-server lookup authenticated with our secret.
 *
 * <p>Amounts here are in the currency's minor unit (paise for INR), the way providers take them; Finance keeps {@code NUMERIC(12,2)}.
 */
public interface PaymentGateway {

    /** False when no provider keys are configured: checkout then answers "not available" instead of pretending. */
    boolean enabled();

    /** The provider's PUBLIC key id, which the browser needs to open the checkout. Never the secret. */
    String publicKeyId();

    Order createOrder(long amountMinor, String currency, String receipt, Map<String, String> notes);

    /** Every payment attempt the provider has seen for an order (an order can be tried more than once). */
    List<ProviderPayment> paymentsForOrder(String orderId);

    ProviderPayment fetchPayment(String providerPaymentId);

    /** Refunds the whole of a captured payment. A payment the provider already shows as refunded is not refunded twice. */
    void refundInFull(String providerPaymentId, long amountMinor, String receipt);

    record Order(String id, long amountMinor, String currency) {
    }

    /** {@code status} is the provider's own word: for Razorpay created, authorized, captured, refunded or failed. */
    record ProviderPayment(String id, String orderId, String status, long amountMinor, String currency) {
        public boolean captured() {
            return "captured".equals(status);
        }

        public boolean refunded() {
            return "refunded".equals(status);
        }
    }

    /** The provider said no, was unreachable, or answered something unreadable. The message never carries a secret. */
    class ProviderException extends RuntimeException {
        public ProviderException(String message) {
            super(message);
        }

        public ProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** No provider keys are configured. */
    class NotConfiguredException extends ProviderException {
        public NotConfiguredException() {
            super("no payment provider is configured");
        }
    }
}
