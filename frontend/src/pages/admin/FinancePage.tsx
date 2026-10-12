import { useMemo, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  FeeStructureResponse, FeeComponentResponse, StudentAccountResponse, StudentChargeResponse,
  StudentResponse, PageResponse, ProgramResponse, AcademicYearResponse, PaymentResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { SearchInput } from '../../components/ui/SearchInput';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { Skeleton } from '../../components/ui/EmptyState';

function FeeStructureComponents({ feeStructureId }: { feeStructureId: string }) {
  const components = useApiQuery<FeeComponentResponse[]>(`/finance/fee-components?feeStructureId=${feeStructureId}`);
  const [form, setForm] = useState({ code: '', amount: '', currency: 'INR' });
  const [message, setMessage] = useState('');
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setMessage('');
    try {
      await apiFetch('/finance/fee-components', { method: 'POST', body: { feeStructureId, code: form.code, amount: Number(form.amount), currency: form.currency } });
      setMessage('Component added.'); setForm({ code: '', amount: '', currency: 'INR' }); components.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not add the component.'); }
  };
  const columns: Column<FeeComponentResponse>[] = [
    { key: 'code', header: 'Component', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'amount', header: 'Amount', render: (c) => `${c.amount} ${c.currency}` },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      <form onSubmit={submit} style={{ display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 12 }}>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor={`comp-code-${feeStructureId}`}>Component code</label><input id={`comp-code-${feeStructureId}`} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} placeholder="TUITION, LAB_FEE, …" required /></div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor={`comp-amount-${feeStructureId}`}>Amount</label><input id={`comp-amount-${feeStructureId}`} type="number" min="0" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} required /></div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor={`comp-currency-${feeStructureId}`}>Currency</label><input id={`comp-currency-${feeStructureId}`} value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })} required /></div>
        <button className="btn btn-primary" style={{ padding: '9px 16px' }}>Add component</button>
      </form>
      {message && <p role="status" style={{ fontSize: 13, marginBottom: 10 }}>{message}</p>}
      <DataTable
        columns={columns}
        rows={components.data}
        loading={components.loading}
        error={components.error}
        onRetry={components.refetch}
        emptyTitle="No fee components"
        rowKey={(c) => c.id}
      />
    </div>
  );
}

function StudentPayments({ studentId, tenantId }: { studentId: string; tenantId: string }) {
  const payments = useApiQuery<PaymentResponse[]>(`/finance/payments?tenantId=${tenantId}&studentId=${studentId}`, [studentId]);
  const [message, setMessage] = useState('');

  const simulate = async (paymentId: string, outcome: 'PAYMENT_SUCCEEDED' | 'PAYMENT_FAILED') => {
    setMessage('');
    try {
      await apiFetch(`/finance/payments/${paymentId}/events`, {
        method: 'POST',
        body: { providerEventId: crypto.randomUUID(), eventType: outcome, payloadJson: '{"simulated":true}' },
      });
      payments.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not simulate the provider webhook.'); }
  };

  const columns: Column<PaymentResponse>[] = [
    { key: 'amount', header: 'Amount', render: (p) => `${p.amount} ${p.currency}` },
    { key: 'method', header: 'Method', render: (p) => p.method },
    { key: 'status', header: 'Status', render: (p) => <StatusBadge value={p.status} /> },
    {
      key: 'actions', header: 'Simulate provider webhook', render: (p) => p.status === 'INITIATED' ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <ConfirmActionButton label="Succeed" confirmMessage="Simulate a successful payment-provider webhook for this payment?" onConfirm={() => simulate(p.id, 'PAYMENT_SUCCEEDED')} />
          <ConfirmActionButton label="Fail" variant="danger" confirmMessage="Simulate a failed payment-provider webhook for this payment?" onConfirm={() => simulate(p.id, 'PAYMENT_FAILED')} />
        </div>
      ) : null,
    },
  ];

  return (
    <div style={{ marginTop: 16 }}>
      <h3 style={{ fontSize: 14, marginBottom: 8 }}>Payments</h3>
      <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: 8 }}>
        No real payment gateway exists — this stands in for the provider&apos;s own confirmation webhook, which only an authorized admin (never the student) can trigger.
      </p>
      {message && <p role="status" style={{ fontSize: 12, color: 'var(--flag)', marginBottom: 8 }}>{message}</p>}
      <DataTable
        columns={columns}
        rows={payments.data}
        loading={payments.loading}
        error={payments.error}
        onRetry={payments.refetch}
        emptyTitle="No payments"
        emptyDescription="This student has not initiated any payments yet."
        rowKey={(p) => p.id}
      />
    </div>
  );
}

function StudentFinance({ studentId, tenantId }: { studentId: string; tenantId: string }) {
  const account = useApiQuery<StudentAccountResponse>(`/finance/accounts?tenantId=${tenantId}&studentId=${studentId}`, [studentId]);
  const charges = useApiQuery<StudentChargeResponse[]>(`/finance/charges?studentId=${studentId}`, [studentId]);
  const structures = useApiQuery<FeeStructureResponse[]>(`/finance/fee-structures?tenantId=${tenantId}`);
  const [feeStructureId, setFeeStructureId] = useState('');
  const components = useApiQuery<FeeComponentResponse[]>(feeStructureId ? `/finance/fee-components?feeStructureId=${feeStructureId}` : null, [feeStructureId]);
  const [feeComponentId, setFeeComponentId] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [chargeMessage, setChargeMessage] = useState('');
  const [chargeSubmitting, setChargeSubmitting] = useState(false);

  const waive = async (chargeId: string) => {
    await apiFetch(`/finance/charges/${chargeId}/waive`, { method: 'POST' });
    charges.refetch();
  };
  const markPaid = async (chargeId: string) => {
    await apiFetch(`/finance/charges/${chargeId}/paid`, { method: 'POST' });
    charges.refetch();
  };
  const assessCharge = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setChargeSubmitting(true); setChargeMessage('');
    try {
      await apiFetch('/finance/charges', { method: 'POST', body: { studentId, feeComponentId, dueDate } });
      setChargeMessage('Charge assessed.'); setFeeComponentId(''); setDueDate(''); charges.refetch();
    } catch (e) { setChargeMessage(e instanceof Error ? e.message : 'Could not assess the charge.'); }
    finally { setChargeSubmitting(false); }
  };

  const columns: Column<StudentChargeResponse>[] = [
    { key: 'amount', header: 'Amount', render: (c) => `${c.amount} ${c.currency}` },
    { key: 'dueDate', header: 'Due', render: (c) => c.dueDate },
    { key: 'status', header: 'Status', render: (c) => <StatusBadge value={c.status} /> },
    {
      key: 'actions',
      header: 'Actions',
      render: (c) => c.status === 'PENDING' || c.status === 'OVERDUE' ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <ConfirmActionButton label="Mark paid" confirmMessage="Mark this charge as paid?" onConfirm={() => markPaid(c.id)} />
          <ConfirmActionButton label="Waive" variant="danger" confirmMessage="Waive this charge? This cannot be undone." onConfirm={() => waive(c.id)} />
        </div>
      ) : null,
    },
  ];

  return (
    <div style={{ marginTop: 16 }}>
      {account.loading ? <Skeleton height={70} /> : account.data ? (
        <div className="card" style={{ marginBottom: 16, maxWidth: 280 }}>
          <div className="stat-label">Account balance</div>
          <div className="mono" style={{ marginTop: 6, fontSize: 20 }}>{account.data.balance} {account.data.currency}</div>
        </div>
      ) : (
        <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 16 }}>This student has no finance account yet.</p>
      )}
      <form onSubmit={assessCharge} className="card" style={{ marginBottom: 16, maxWidth: 480 }}>
        <h3 style={{ fontSize: 14, marginBottom: 8 }}>Assess a charge</h3>
        <div className="form-field">
          <label htmlFor="charge-structure">Fee structure</label>
          <select id="charge-structure" value={feeStructureId} onChange={(e) => { setFeeStructureId(e.target.value); setFeeComponentId(''); }} required>
            <option value="">Choose fee structure</option>
            {structures.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
        <div className="form-field">
          <label htmlFor="charge-component">Component</label>
          <select id="charge-component" value={feeComponentId} onChange={(e) => setFeeComponentId(e.target.value)} required disabled={!feeStructureId}>
            <option value="">{feeStructureId ? 'Choose component' : 'Choose a fee structure first'}</option>
            {components.data?.map((c) => <option key={c.id} value={c.id}>{c.code} — {c.amount} {c.currency}</option>)}
          </select>
        </div>
        <div className="form-field"><label htmlFor="charge-due">Due date</label><input id="charge-due" type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} required /></div>
        {chargeMessage && <p role="status" style={{ marginBottom: 10 }}>{chargeMessage}</p>}
        <button type="submit" className="btn btn-primary" disabled={chargeSubmitting || !feeComponentId}>{chargeSubmitting ? 'Assessing…' : 'Assess charge'}</button>
      </form>
      <DataTable
        columns={columns}
        rows={charges.data}
        loading={charges.loading}
        error={charges.error}
        onRetry={charges.refetch}
        emptyTitle="No charges"
        emptyDescription="This student has no fee charges on record."
        rowKey={(c) => c.id}
      />
      <StudentPayments studentId={studentId} tenantId={tenantId} />
    </div>
  );
}

export default function FinancePage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [expandedStructure, setExpandedStructure] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [selectedStudent, setSelectedStudent] = useState<{ id: string; name: string } | null>(null);

  const structures = useApiQuery<FeeStructureResponse[]>(tenantId ? `/finance/fee-structures?tenantId=${tenantId}` : null);
  const programs = useApiQuery<ProgramResponse[]>(tenantId ? `/catalog/programs?tenantId=${tenantId}` : null);
  const years = useApiQuery<AcademicYearResponse[]>(tenantId ? `/catalog/academic-years?tenantId=${tenantId}` : null);
  const students = useApiQuery<PageResponse<StudentResponse>>(
    tenantId ? `/directory/students?tenantId=${tenantId}&size=500` : null,
  );
  const programNameFor = (programId: string) => programs.data?.find((p) => p.id === programId)?.name ?? programId;

  const [structureForm, setStructureForm] = useState({ programId: '', name: '', academicYearId: '' });
  const [structureMessage, setStructureMessage] = useState('');
  const [structureSubmitting, setStructureSubmitting] = useState(false);
  const createStructure = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!tenantId) return; setStructureSubmitting(true); setStructureMessage('');
    try {
      await apiFetch('/finance/fee-structures', { method: 'POST', body: { tenantId, ...structureForm } });
      setStructureMessage('Fee structure created.'); setStructureForm({ programId: '', name: '', academicYearId: '' }); structures.refetch();
    } catch (e) { setStructureMessage(e instanceof Error ? e.message : 'Could not create the fee structure.'); }
    finally { setStructureSubmitting(false); }
  };

  const filteredStudents = useMemo(() => {
    if (!students.data) return [];
    if (!search.trim()) return [];
    const q = search.toLowerCase();
    return students.data.content.filter((s) => s.fullName?.toLowerCase().includes(q) || s.email?.toLowerCase().includes(q)).slice(0, 8);
  }, [students.data, search]);

  const structureColumns: Column<FeeStructureResponse>[] = [
    { key: 'name', header: 'Name', render: (f) => f.name },
    { key: 'program', header: 'Program', render: (f) => programNameFor(f.programId) },
    { key: 'status', header: 'Status', render: (f) => <StatusBadge value={f.status} /> },
    {
      key: 'actions',
      header: '',
      render: (f) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpandedStructure(expandedStructure === f.id ? null : f.id)}>
          Components
        </button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Finance" subtitle="Fee structures, student accounts, charges, and payment status." />

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>Fee structures</h2>
      <form onSubmit={createStructure} className="card" style={{ marginBottom: 16, maxWidth: 480 }}>
        <h3 style={{ fontSize: 14, marginBottom: 8 }}>Create fee structure</h3>
        <div className="form-field">
          <label htmlFor="structure-program">Program</label>
          <select id="structure-program" value={structureForm.programId} onChange={(e) => setStructureForm({ ...structureForm, programId: e.target.value })} required>
            <option value="">Choose program</option>
            {programs.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="form-field">
          <label htmlFor="structure-year">Academic year</label>
          <select id="structure-year" value={structureForm.academicYearId} onChange={(e) => setStructureForm({ ...structureForm, academicYearId: e.target.value })} required>
            <option value="">Choose academic year</option>
            {years.data?.map((y) => <option key={y.id} value={y.id}>{y.code}</option>)}
          </select>
        </div>
        <div className="form-field"><label htmlFor="structure-name">Name</label><input id="structure-name" value={structureForm.name} onChange={(e) => setStructureForm({ ...structureForm, name: e.target.value })} required /></div>
        {structureMessage && <p role="status" style={{ marginBottom: 10 }}>{structureMessage}</p>}
        <button type="submit" className="btn btn-primary" disabled={structureSubmitting}>{structureSubmitting ? 'Creating…' : 'Create fee structure'}</button>
      </form>
      <DataTable
        columns={structureColumns}
        rows={structures.data}
        loading={structures.loading || programs.loading}
        error={structures.error}
        onRetry={structures.refetch}
        emptyTitle="No fee structures"
        emptyDescription="No fee structures have been created yet."
        rowKey={(f) => f.id}
      />
      {expandedStructure && <FeeStructureComponents feeStructureId={expandedStructure} />}

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Student account lookup</h2>
      <SearchInput value={search} onChange={setSearch} label="Find a student" placeholder="Name or email" />
      {filteredStudents.length > 0 && !selectedStudent && (
        <div className="card" style={{ padding: 8, marginBottom: 16 }}>
          {filteredStudents.map((s) => (
            <button key={s.id} type="button" className="btn btn-ghost" style={{ display: 'block', width: '100%', textAlign: 'left', marginBottom: 4 }}
              onClick={() => { setSelectedStudent({ id: s.id, name: s.fullName ?? s.email ?? s.id }); setSearch(''); }}>
              {s.fullName ?? s.email}
            </button>
          ))}
        </div>
      )}
      {selectedStudent && tenantId && (
        <>
          <p style={{ fontSize: 14, marginBottom: 4 }}>
            <strong>{selectedStudent.name}</strong>{' '}
            <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12, marginLeft: 8 }}
              onClick={() => setSelectedStudent(null)}>Change</button>
          </p>
          <StudentFinance studentId={selectedStudent.id} tenantId={tenantId} />
        </>
      )}
    </div>
  );
}
