import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CHECKOUT_SCRIPT_URL, openCheckout, type CheckoutSession } from '../lib/razorpayCheckout';

const session: CheckoutSession = { paymentId: 'p1', keyId: 'rzp_test_public', orderId: 'order_1', amount: 200000, currency: 'INR', description: 'Fee payment' };

/** A stand-in for Razorpay's script: records how it was opened and lets the test play what the person does in the window. */
function fakeRazorpay() {
  const made: { options: Record<string, any>; handlers: Record<string, (r: any) => void>; opened: boolean }[] = [];
  window.Razorpay = class {
    private entry: (typeof made)[number];
    constructor(options: Record<string, unknown>) {
      this.entry = { options, handlers: {}, opened: false };
      made.push(this.entry);
    }
    open() { this.entry.opened = true; }
    on(event: string, handler: (r: any) => void) { this.entry.handlers[event] = handler; }
  } as unknown as typeof window.Razorpay;
  return made;
}

describe('Razorpay checkout loader', () => {
  beforeEach(() => { delete window.Razorpay; document.head.querySelectorAll('script').forEach((s) => s.remove()); });
  afterEach(() => { delete window.Razorpay; });

  it('opens the window for the order the server created, with the public key only', async () => {
    const made = fakeRazorpay();
    const pending = openCheckout(session, 'Setu College');
    await vi.waitFor(() => expect(made[0]?.opened).toBe(true));

    expect(made[0].options).toMatchObject({ key: 'rzp_test_public', order_id: 'order_1', amount: 200000, currency: 'INR', name: 'Setu College' });
    expect(Object.keys(made[0].options).join(' ')).not.toMatch(/secret|signature/i);
    made[0].options.modal.ondismiss();
    await pending;
  });

  it('resolves "paid" when the window says so, and reads nothing the window hands over', async () => {
    const made = fakeRazorpay();
    const pending = openCheckout(session, 'Setu College');
    await vi.waitFor(() => expect(made[0]?.opened).toBe(true));

    made[0].options.handler({ razorpay_payment_id: 'pay_1', razorpay_order_id: 'order_1', razorpay_signature: 'sig' });

    await expect(pending).resolves.toEqual({ kind: 'paid' });
  });

  it('resolves "dismissed" with the last failure reason when the person closes the window after a declined attempt', async () => {
    const made = fakeRazorpay();
    const pending = openCheckout(session, 'Setu College');
    await vi.waitFor(() => expect(made[0]?.opened).toBe(true));

    made[0].handlers['payment.failed']({ error: { description: 'Your card was declined.' } });
    made[0].options.modal.ondismiss();

    await expect(pending).resolves.toEqual({ kind: 'dismissed', lastFailure: 'Your card was declined.' });
  });

  it('keeps the first outcome: a dismiss after a success does not turn a payment into a cancellation', async () => {
    const made = fakeRazorpay();
    const pending = openCheckout(session, 'Setu College');
    await vi.waitFor(() => expect(made[0]?.opened).toBe(true));

    made[0].options.handler({});
    made[0].options.modal.ondismiss();

    await expect(pending).resolves.toEqual({ kind: 'paid' });
  });

  it('loads the provider script from Razorpay once, and reuses it', async () => {
    vi.resetModules();   // the loader remembers a finished load for the life of the page
    const { loadCheckout } = await import('../lib/razorpayCheckout');
    const first = loadCheckout();
    const second = loadCheckout();
    const scripts = document.head.querySelectorAll(`script[src="${CHECKOUT_SCRIPT_URL}"]`);
    expect(scripts).toHaveLength(1);

    fakeRazorpay();
    (scripts[0] as HTMLScriptElement).onload!(new Event('load'));
    expect(await first).toBe(await second);
  });

  it('says so, in words, when the script cannot be loaded, and tries again next time', async () => {
    vi.resetModules();
    const { loadCheckout } = await import('../lib/razorpayCheckout');
    const failing = loadCheckout();
    (document.head.querySelector(`script[src="${CHECKOUT_SCRIPT_URL}"]`) as HTMLScriptElement).onerror!(new Event('error'));
    await expect(failing).rejects.toThrow('could not be loaded');

    document.head.querySelectorAll('script').forEach((s) => s.remove());
    const again = loadCheckout();
    expect(document.head.querySelectorAll(`script[src="${CHECKOUT_SCRIPT_URL}"]`)).toHaveLength(1);
    fakeRazorpay();
    (document.head.querySelector(`script[src="${CHECKOUT_SCRIPT_URL}"]`) as HTMLScriptElement).onload!(new Event('load'));
    await expect(again).resolves.toBeTruthy();
  });
});
