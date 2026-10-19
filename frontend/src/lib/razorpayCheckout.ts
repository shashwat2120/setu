/**
 * Razorpay's hosted checkout. The card, UPI or net-banking details are typed into Razorpay's own window and go from the browser straight to
 * Razorpay: this app never receives them, so it cannot store or leak them. All this module does is load Razorpay's script and open the window for
 * an order that OUR server created (the amount is the order's, not something the browser can change).
 *
 * What it resolves to is only what the person did. "paid" means the window said so, which is NOT proof: the page must ask the server, which asks the
 * provider, before it shows anything as paid (canonical spec §5: the browser never decides).
 */

export interface CheckoutSession {
  paymentId: string;
  keyId: string;
  orderId: string;
  /** In the currency's minor unit (paise for INR), as the provider takes it. */
  amount: number;
  currency: string;
  description: string;
}

export type CheckoutOutcome =
  | { kind: 'paid' }
  /** The window was closed without a payment going through. `lastFailure` is the provider's reason for the last failed attempt, if there was one. */
  | { kind: 'dismissed'; lastFailure: string | null };

interface RazorpayInstance {
  open(): void;
  on(event: string, handler: (response: { error?: { description?: string } }) => void): void;
}

type RazorpayConstructor = new (options: Record<string, unknown>) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

export const CHECKOUT_SCRIPT_URL = 'https://checkout.razorpay.com/v1/checkout.js';

let loading: Promise<RazorpayConstructor> | null = null;

/** Loads Razorpay's script once. A failure is not remembered, so going back online and trying again works. */
export function loadCheckout(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return Promise.resolve(window.Razorpay);
  if (loading) return loading;
  loading = new Promise<RazorpayConstructor>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = CHECKOUT_SCRIPT_URL;
    script.async = true;
    script.onload = () => {
      if (window.Razorpay) resolve(window.Razorpay);
      else reject(new Error('The payment window did not load.'));
    };
    script.onerror = () => reject(new Error('The payment window could not be loaded. Check your connection and try again.'));
    document.head.appendChild(script);
  }).catch((e) => {
    loading = null;
    throw e;
  });
  return loading;
}

export async function openCheckout(session: CheckoutSession, merchantName: string): Promise<CheckoutOutcome> {
  const Razorpay = await loadCheckout();
  return new Promise<CheckoutOutcome>((resolve) => {
    let lastFailure: string | null = null;
    let settled = false;
    const finish = (outcome: CheckoutOutcome) => {
      if (!settled) {
        settled = true;
        resolve(outcome);
      }
    };
    const checkout = new Razorpay({
      key: session.keyId,
      order_id: session.orderId,
      amount: session.amount,
      currency: session.currency,
      name: merchantName,
      description: session.description,
      // The handler receives the provider's payment id and signature. They are deliberately not read: the server learns the result from the
      // provider itself, never from what the browser reports.
      handler: () => finish({ kind: 'paid' }),
      modal: { ondismiss: () => finish({ kind: 'dismissed', lastFailure }) },
    });
    checkout.on('payment.failed', (response) => {
      lastFailure = response.error?.description ?? 'The payment did not go through.';
    });
    checkout.open();
  });
}
