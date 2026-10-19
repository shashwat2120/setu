/**
 * How long the Finance page waits for the payment provider to confirm a payment after the checkout window says "paid": it asks the server, which asks
 * the provider, then re-reads the payment's status this many times. An object, not constants, only so a test can make the wait short.
 */
export const paymentPolling = { everyMs: 2000, polls: 5 };
