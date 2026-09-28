import { useMemo, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  FeeStructureResponse, FeeComponentResponse, StudentAccountResponse, StudentChargeResponse,
  StudentResponse, PageResponse, ProgramResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { SearchInput } from '../../components/ui/SearchInput';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { Skeleton } from '../../components/ui/EmptyState';

function FeeStructureComponents({ feeStructureId }: { feeStructureId: string }) {
  const components = useApiQuery<FeeComponentResponse[]>(`/finance/fee-components?feeStructureId=${feeStructureId}`);
  const columns: Column<FeeComponentResponse>[] = [
    { key: 'code', header: 'Component', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'amount', header: 'Amount', render: (c) => `${c.amount} ${c.currency}` },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
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

function StudentFinance({ studentId, tenantId }: { studentId: string; tenantId: string }) {
  const account = useApiQuery<StudentAccountResponse>(`/finance/accounts?tenantId=${tenantId}&studentId=${studentId}`, [studentId]);
  const charges = useApiQuery<StudentChargeResponse[]>(`/finance/charges?studentId=${studentId}`, [studentId]);

  const waive = async (chargeId: string) => {
    await apiFetch(`/finance/charges/${chargeId}/waive`, { method: 'POST' });
    charges.refetch();
  };
  const markPaid = async (chargeId: string) => {
    await apiFetch(`/finance/charges/${chargeId}/paid`, { method: 'POST' });
    charges.refetch();
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
  const students = useApiQuery<PageResponse<StudentResponse>>(
    tenantId ? `/directory/students?tenantId=${tenantId}&size=500` : null,
  );
  const programNameFor = (programId: string) => programs.data?.find((p) => p.id === programId)?.name ?? programId;

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
