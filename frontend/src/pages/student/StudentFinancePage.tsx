import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  StudentAccountResponse, StudentChargeResponse, PaymentInitiateResponse, PaymentCheckoutResponse, PaymentReconcileResponse, PaymentStatusResponse, ReceiptResponse,
} from '../../api/types';
import { openCheckout } from '../../lib/razorpayCheckout';
import { paymentPolling } from '../../lib/paymentPolling';
import { PageHeader } from '../../components/ui/PageHeader';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton } from '../../components/ui/EmptyState';

type Phase =
  | { kind: 'idle' }
  | { kind: 'working'; label: string }
  | { kind: 'closed'; lastFailure: string | null }
  | { kind: 'confirming' }
  | { kind: 'unconfirmed' }
  | { kind: 'confirmed' };

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function ChargeRow({ charge, studentId, onPaid }: { charge: StudentChargeResponse; studentId: string; onPaid: () => void }) {
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [error, setError] = useState<string | null>(null);
  const [paymentId, setPaymentId] = useState<string | null>(null);
  const receipt = useApiQuery<ReceiptResponse>(paymentId && phase.kind === 'confirmed' ? `/finance/receipts/by-payment/${paymentId}` : null, [paymentId, phase.kind]);

  /**
   * Asks the server what became of the payment. The server asks the payment provider, and the status it answers with changes only when the
   * provider confirms; what the checkout window said is only the reason to ask. Never shows 'paid' on the window's word.
   */
  const confirm = async (id: string) => {
    setPhase({ kind: 'confirming' });
    try {
      let status = (await apiFetch<PaymentReconcileResponse>(`/finance/payments/${id}/reconcile`, { method: 'POST' })).status;
      for (let i = 0; status === 'INITIATED' && i < paymentPolling.polls; i++) {
        await sleep(paymentPolling.everyMs);
        status = (await apiFetch<PaymentStatusResponse>(`/finance/payments/${id}`)).status;
      }
      if (status === 'SUCCEEDED') {
        setPhase({ kind: 'confirmed' });
        onPaid();
      } else {
        setPhase({ kind: 'unconfirmed' });
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not check the payment.');
      setPhase({ kind: 'unconfirmed' });
    }
  };

  const pay = async () => {
    setError(null);
    setPhase({ kind: 'working', label: 'Starting…' });
    try {
      // The amount is the fee's, decided by the server from the charge; only the charge is named here.
      const started = await apiFetch<PaymentInitiateResponse>('/finance/payments', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: { studentId, chargeId: charge.id, method: 'CHECKOUT' },
      });
      setPaymentId(started.paymentId);
      const session = await apiFetch<PaymentCheckoutResponse>(`/finance/payments/${started.paymentId}/checkout`, { method: 'POST' });
      setPhase({ kind: 'working', label: 'Opening payment window…' });
      const outcome = await openCheckout(session, 'Setu College');
      if (outcome.kind === 'paid') {
        await confirm(started.paymentId);
      } else {
        setPhase({ kind: 'closed', lastFailure: outcome.lastFailure });
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start the payment.');
      setPhase({ kind: 'idle' });
    }
  };

  const payable = charge.status === 'PENDING' || charge.status === 'OVERDUE';
  const busy = phase.kind === 'working' || phase.kind === 'confirming';
  return (
    <tr>
      <td data-label="Amount"><span className="mono">{charge.amount} {charge.currency}</span></td>
      <td data-label="Due">{charge.dueDate}</td>
      <td data-label="Status"><StatusBadge value={charge.status} /></td>
      <td data-label="Actions">
        {payable && phase.kind !== 'confirmed' && phase.kind !== 'unconfirmed' && (
          <button type="button" className="btn btn-primary" style={{ padding: '6px 12px', fontSize: 12 }} disabled={busy} onClick={pay}
            aria-label={`Pay ${charge.amount} ${charge.currency}, due ${charge.dueDate}`}>
            {phase.kind === 'working' ? phase.label : phase.kind === 'confirming' ? 'Confirming…' : 'Pay'}
          </button>
        )}
        {phase.kind === 'closed' && (
          <p role="status" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>
            {phase.lastFailure ? `The last attempt did not go through: ${phase.lastFailure} ` : ''}
            The payment window was closed. No payment has been confirmed, so nothing is marked paid; you can pay again when you are ready.
          </p>
        )}
        {phase.kind === 'confirming' && (
          <p role="status" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>Confirming your payment with the provider…</p>
        )}
        {phase.kind === 'unconfirmed' && paymentId && (
          <div>
            <p role="status" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>
              The provider has not confirmed this payment yet. If money has left your account it will show here once they do. Please do not pay again.
            </p>
            <button type="button" className="btn" style={{ padding: '6px 12px', fontSize: 12 }} onClick={() => confirm(paymentId)}
              aria-label={`Check payment of ${charge.amount} ${charge.currency} again`}>
              Check again
            </button>
          </div>
        )}
        {phase.kind === 'confirmed' && (
          <p role="status" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>
            Payment confirmed by the provider.{receipt.data ? ` Receipt ${receipt.data.receiptNo}.` : ''}
          </p>
        )}
        {error && <div role="alert" style={{ fontSize: 12, color: 'var(--flag)' }}>{error}</div>}
      </td>
    </tr>
  );
}

export default function StudentFinancePage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'STUDENT');
  const studentId = user?.studentId ?? null;

  const account = useApiQuery<StudentAccountResponse>(tenantId && studentId ? `/finance/accounts?tenantId=${tenantId}&studentId=${studentId}` : null);
  const charges = useApiQuery<StudentChargeResponse[]>(studentId ? `/finance/charges?studentId=${studentId}` : null);

  return (
    <div>
      <PageHeader title="Finance" subtitle="Charges, payments, and receipts." />

      {account.loading ? <Skeleton height={70} /> : account.data ? (
        <div className="card" style={{ marginBottom: 20, maxWidth: 280 }}>
          <div className="stat-label">Account balance</div>
          <div className="mono" style={{ marginTop: 6, fontSize: 22 }}>{account.data.balance} {account.data.currency}</div>
        </div>
      ) : (
        <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 20 }}>You don't have a finance account set up yet.</p>
      )}

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>Charges</h2>
      {charges.loading && <Skeleton height={80} />}
      {charges.error && <div className="card"><p role="alert" style={{ color: 'var(--flag)' }}>Could not load your charges.</p></div>}
      {charges.data && charges.data.length === 0 && <div className="card"><p style={{ color: 'var(--ink-soft)' }}>You have no fee charges on record.</p></div>}
      {charges.data && charges.data.length > 0 && studentId && (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <table className="data-table">
            <thead><tr><th scope="col">Amount</th><th scope="col">Due</th><th scope="col">Status</th><th scope="col">Actions</th></tr></thead>
            <tbody>
              {charges.data.map((c) => <ChargeRow key={c.id} charge={c} studentId={studentId} onPaid={charges.refetch} />)}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
