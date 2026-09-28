import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { StudentAccountResponse, StudentChargeResponse, PaymentInitiateResponse, ReceiptResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton } from '../../components/ui/EmptyState';

function ChargeRow({ charge, studentId, onPaid }: { charge: StudentChargeResponse; studentId: string; onPaid: () => void }) {
  const [paying, setPaying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [paymentId, setPaymentId] = useState<string | null>(null);
  const receipt = useApiQuery<ReceiptResponse>(paymentId ? `/finance/receipts/by-payment/${paymentId}` : null, [paymentId]);

  const pay = async () => {
    setPaying(true);
    setError(null);
    try {
      const result = await apiFetch<PaymentInitiateResponse>('/finance/payments', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: { studentId, amount: charge.amount, currency: charge.currency, method: 'CARD' },
      });
      setPaymentId(result.paymentId);
      onPaid();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not initiate payment.');
    } finally {
      setPaying(false);
    }
  };

  return (
    <tr>
      <td data-label="Amount"><span className="mono">{charge.amount} {charge.currency}</span></td>
      <td data-label="Due">{charge.dueDate}</td>
      <td data-label="Status"><StatusBadge value={charge.status} /></td>
      <td data-label="Actions">
        {charge.status === 'PENDING' || charge.status === 'OVERDUE' ? (
          <button type="button" className="btn btn-primary" style={{ padding: '6px 12px', fontSize: 12 }} disabled={paying} onClick={pay}>
            {paying ? 'Processing…' : 'Pay'}
          </button>
        ) : charge.status === 'PAID' && paymentId ? (
          receipt.data ? <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>Receipt {receipt.data.receiptNo}</span> : null
        ) : null}
        {error && <div style={{ fontSize: 12, color: 'var(--flag)' }}>{error}</div>}
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
      {charges.error && <div className="card"><p style={{ color: 'var(--flag)' }}>Could not load your charges.</p></div>}
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
