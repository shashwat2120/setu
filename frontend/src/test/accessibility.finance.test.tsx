import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from '../api/client';
import StudentFinancePage from '../pages/student/StudentFinancePage';
import { openCheckout } from '../lib/razorpayCheckout';
import { paymentPolling } from '../lib/paymentPolling';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

vi.mock('../lib/razorpayCheckout', () => ({ openCheckout: vi.fn() }));

const charge = (id: string, amount: number, due: string, status: string) => ({
  id, tenantId: TENANT, studentId: 's', feeComponentId: 'f', amount, currency: 'INR', dueDate: due, status,
});

function financeApi(extra: Record<string, unknown> = {}) {
  mockApi({
    '/finance/accounts': { id: 'a', tenantId: TENANT, studentId: 's', balance: 3500, currency: 'INR' },
    '/finance/charges': [charge('c1', 2000, '2026-10-09', 'OVERDUE'), charge('c2', 1500, '2026-12-01', 'PENDING'), charge('c3', 500, '2026-08-01', 'PAID')],
    ...extra,
  });
}

describe('student finance (spec section 13: tables, forms and errors)', () => {
  beforeEach(() => {
    if (!globalThis.crypto?.randomUUID) vi.stubGlobal('crypto', { randomUUID: () => 'idempotency-key-for-the-test' });
  });

  it('has no WCAG 2.2 AA violations an automated engine can find, with charges of every status', async () => {
    signedInAs('STUDENT');
    financeApi();
    const { container } = renderPage(<StudentFinancePage />);
    await screen.findByText('2000 INR');
    await expectAccessible(container);
  });

  it('gives each Pay button a name that says which charge it pays, so they can be told apart', async () => {
    signedInAs('STUDENT');
    financeApi();
    renderPage(<StudentFinancePage />);
    await screen.findByText('2000 INR');

    const pay = screen.getAllByRole('button', { name: /^Pay / });
    expect(pay.map((b) => b.getAttribute('aria-label'))).toEqual(['Pay 2000 INR, due 2026-10-09', 'Pay 1500 INR, due 2026-12-01']);
    for (const b of pay) expect(b.textContent).toBe('Pay');   // the visible word is part of the name (WCAG 2.5.3)
  });

  it('keeps the table readable to a screen reader: scoped column headers, one row per charge', async () => {
    signedInAs('STUDENT');
    financeApi();
    renderPage(<StudentFinancePage />);
    const table = await screen.findByRole('table');
    const headers = within(table).getAllByRole('columnheader');
    expect(headers.map((h) => h.textContent)).toEqual(['Amount', 'Due', 'Status', 'Actions']);
    for (const h of headers) expect(h.getAttribute('scope')).toBe('col');
    expect(within(table).getAllByRole('row')).toHaveLength(4);   // header + three charges
  });

  const session = { paymentId: 'p1', keyId: 'rzp_test_public', orderId: 'order_1', amount: 200000, currency: 'INR', description: 'Fee payment' };
  const startable = (extra: Record<string, unknown> = {}) => financeApi({
    '/finance/payments': { paymentId: 'p1', replayed: false },
    '/finance/payments/p1/checkout': session,
    '/finance/receipts/by-payment': { id: 'r', paymentId: 'p1', receiptNo: 'RCT-1', issuedAt: '2026-10-20T00:00:00Z', documentRef: null },
    ...extra,
  });
  const payFirst = async (user: ReturnType<typeof userEvent.setup>) => {
    await screen.findByText('2000 INR');
    await user.click(screen.getByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' }));
  };

  beforeEach(() => {
    vi.mocked(openCheckout).mockReset();
    paymentPolling.everyMs = 1;
    paymentPolling.polls = 2;
  });

  it("names the charge only when starting a payment, never an amount the browser could change", async () => {
    signedInAs('STUDENT');
    startable({ '/finance/payments/p1/reconcile': { status: 'SUCCEEDED', changed: true } });
    vi.mocked(openCheckout).mockResolvedValue({ kind: 'paid' });
    const user = userEvent.setup();
    renderPage(<StudentFinancePage />);

    await payFirst(user);

    await screen.findByText(/Payment confirmed by the provider/);
    const start = vi.mocked(apiFetch).mock.calls.find(([path, init]) => path === '/finance/payments' && init?.method === 'POST')!;
    expect(start[1]!.body).toEqual({ studentId: '44444444-4444-4444-8444-444444444444', chargeId: 'c1', method: 'CHECKOUT' });
    expect(vi.mocked(openCheckout)).toHaveBeenCalledWith(session, expect.any(String));
  });

  it('shows a payment as confirmed only after the server has asked the provider, and shows the receipt', async () => {
    signedInAs('STUDENT');
    startable({ '/finance/payments/p1/reconcile': { status: 'SUCCEEDED', changed: true } });
    vi.mocked(openCheckout).mockResolvedValue({ kind: 'paid' });
    const user = userEvent.setup();
    const { container } = renderPage(<StudentFinancePage />);

    await payFirst(user);

    expect((await screen.findByText(/Payment confirmed by the provider/)).textContent).toContain('RCT-1');
    expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/finance/payments/p1/reconcile', { method: 'POST' });
    await expectAccessible(container);
  });

  it('does not believe the checkout window: if the provider has not confirmed, it says so and does not call it paid', async () => {
    signedInAs('STUDENT');
    startable({
      '/finance/payments/p1/reconcile': { status: 'INITIATED', changed: false },
      '/finance/payments/p1': { id: 'p1', chargeId: 'c1', amount: 2000, currency: 'INR', status: 'INITIATED', checkoutStarted: true },
    });
    vi.mocked(openCheckout).mockResolvedValue({ kind: 'paid' });
    const user = userEvent.setup();
    const { container } = renderPage(<StudentFinancePage />);

    await payFirst(user);

    const note = await screen.findByText(/The provider has not confirmed this payment yet/);
    expect(note.textContent).toContain('do not pay again');
    expect(screen.queryByText(/Payment confirmed/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' })).toBeNull();   // no second payment is offered while one is unconfirmed
    await expectAccessible(container);
  });

  it('lets the person check again, and confirms once the provider has', async () => {
    signedInAs('STUDENT');
    let confirmed = false;
    startable({
      '/finance/payments/p1/reconcile': () => ({ status: confirmed ? 'SUCCEEDED' : 'INITIATED', changed: confirmed }),
      '/finance/payments/p1': { id: 'p1', chargeId: 'c1', amount: 2000, currency: 'INR', status: 'INITIATED', checkoutStarted: true },
    });
    vi.mocked(openCheckout).mockResolvedValue({ kind: 'paid' });
    const user = userEvent.setup();
    renderPage(<StudentFinancePage />);
    await payFirst(user);
    await screen.findByText(/has not confirmed this payment yet/);

    confirmed = true;
    await user.click(screen.getByRole('button', { name: 'Check payment of 2000 INR again' }));

    await screen.findByText(/Payment confirmed by the provider/);
  });

  it('says nothing was paid when the window is closed, and offers to pay again', async () => {
    signedInAs('STUDENT');
    startable();
    vi.mocked(openCheckout).mockResolvedValue({ kind: 'dismissed', lastFailure: 'Your card was declined.' });
    const user = userEvent.setup();
    const { container } = renderPage(<StudentFinancePage />);

    await payFirst(user);

    const note = await screen.findByText(/The payment window was closed/);
    expect(note.textContent).toContain('Your card was declined.');
    expect(note.textContent).toContain('No payment has been confirmed');
    expect(screen.getByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' })).toBeTruthy();
    expect(vi.mocked(apiFetch)).not.toHaveBeenCalledWith('/finance/payments/p1/reconcile', expect.anything());
    await expectAccessible(container);
  });

  it('announces, as an alert, that online payment is off, and leaves the button there', async () => {
    signedInAs('STUDENT');
    startable({ '/finance/payments/p1/checkout': () => { throw new ApiError(503, 'online payment is not enabled for this college'); } });
    const user = userEvent.setup();
    const { container } = renderPage(<StudentFinancePage />);

    await payFirst(user);

    expect((await screen.findByRole('alert')).textContent).toBe('online payment is not enabled for this college');
    expect(vi.mocked(openCheckout)).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' })).toBeTruthy());
    await expectAccessible(container);
  });

  it('announces that the payment window could not be loaded as an alert and lets the person try again', async () => {
    signedInAs('STUDENT');
    startable();
    vi.mocked(openCheckout).mockRejectedValue(new Error('The payment window could not be loaded. Check your connection and try again.'));
    const user = userEvent.setup();
    renderPage(<StudentFinancePage />);

    await payFirst(user);

    expect((await screen.findByRole('alert')).textContent).toContain('could not be loaded');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' })).toBeTruthy());
  });

  it('announces a failed start as an alert and leaves the button there to try again', async () => {
    signedInAs('STUDENT');
    financeApi({ '/finance/payments': () => { throw new ApiError(503, 'The payment service is unavailable.'); } });
    const user = userEvent.setup();
    const { container } = renderPage(<StudentFinancePage />);
    await screen.findByText('2000 INR');

    await user.click(screen.getByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' }));

    expect((await screen.findByRole('alert')).textContent).toBe('The payment service is unavailable.');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' })).toBeTruthy());
    await expectAccessible(container);
  });

  it('says plainly when there is no finance account, and when the charges cannot be loaded', async () => {
    signedInAs('STUDENT');
    mockApi({});   // every request is a 404
    const { container } = renderPage(<StudentFinancePage />);
    expect(await screen.findByText(/You don't have a finance account set up yet/)).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toContain('Could not load your charges');
    await expectAccessible(container);
    expect(vi.mocked(apiFetch)).toHaveBeenCalled();
  });
});
