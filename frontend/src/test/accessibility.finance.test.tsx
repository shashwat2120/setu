import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from '../api/client';
import StudentFinancePage from '../pages/student/StudentFinancePage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

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

  it('announces that a payment was started, and does not claim it is paid (the provider confirms that)', async () => {
    signedInAs('STUDENT');
    financeApi({ '/finance/payments': { paymentId: 'p1', replayed: false } });
    const user = userEvent.setup();
    renderPage(<StudentFinancePage />);
    await screen.findByText('2000 INR');

    await user.click(screen.getByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' }));

    const note = await screen.findByRole('status');
    expect(note.textContent).toContain('awaiting the provider');
    expect(screen.queryByRole('button', { name: 'Pay 2000 INR, due 2026-10-09' })).toBeNull();
  });

  it('announces a failed payment as an alert and leaves the button there to try again', async () => {
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
