import { useMemo, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { DepartmentResponse, OrganizationResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { SearchInput } from '../../components/ui/SearchInput';
import { apiFetch } from '../../api/client';

function AssignHod({ departmentId, onAssigned }: { departmentId: string; onAssigned: () => void }) {
  const [hodUserId, setHodUserId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState('');
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setSubmitting(true); setMessage('');
    try {
      await apiFetch(`/institution/departments/${departmentId}/hod`, { method: 'POST', body: { hodUserId } });
      setMessage('HOD assigned.'); setHodUserId(''); onAssigned();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not assign HOD.'); }
    finally { setSubmitting(false); }
  };
  return (
    <form onSubmit={submit} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
      <input value={hodUserId} onChange={(e) => setHodUserId(e.target.value)} placeholder="Faculty user ID (from their detail page)"
        style={{ fontSize: 12, padding: '5px 8px', border: '1px solid var(--rule)', borderRadius: 4, width: 220 }} required />
      <button type="submit" className="btn btn-ghost" style={{ padding: '5px 10px', fontSize: 12 }} disabled={submitting}>
        {submitting ? 'Assigning…' : 'Assign'}
      </button>
      {message && <span style={{ fontSize: 11, color: 'var(--ink-soft)' }}>{message}</span>}
    </form>
  );
}

export default function DepartmentsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [search, setSearch] = useState('');
  const [orgId, setOrgId] = useState('');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [message, setMessage] = useState('');

  const departments = useApiQuery<DepartmentResponse[]>(tenantId ? `/institution/departments?tenantId=${tenantId}` : null);
  const organizations = useApiQuery<OrganizationResponse[]>(
    tenantId ? `/institution/academic-organizations?tenantId=${tenantId}` : null,
  );

  const filtered = useMemo(() => {
    if (!departments.data) return null;
    if (!search.trim()) return departments.data;
    const q = search.toLowerCase();
    return departments.data.filter((d) => d.name.toLowerCase().includes(q));
  }, [departments.data, search]);

  const columns: Column<DepartmentResponse>[] = [
    { key: 'name', header: 'Name', render: (d) => d.name },
    {
      key: 'organization',
      header: 'Organization',
      render: (d) => organizations.data?.find((o) => o.id === d.organizationId)?.name ?? d.organizationId,
    },
    {
      key: 'hod',
      header: 'Head of Department',
      render: (d) => d.hodUserId
        ? <span className="mono" style={{ fontSize: 12 }}>{d.hodUserId}</span>
        : <AssignHod departmentId={d.id} onAssigned={departments.refetch} />,
    },
  ];

  async function createDepartment(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try { await apiFetch('/institution/departments', { method: 'POST', body: { organizationId: orgId, code, name } }); setName(''); setCode(''); setMessage('Department created.'); departments.refetch(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create department.'); }
  }

  return (
    <div>
      <PageHeader title="Departments" subtitle={departments.data ? `${departments.data.length} departments` : undefined} />
      <form className="card" onSubmit={createDepartment} style={{ marginBottom: 18 }}>
        <h2 style={{ fontSize: 17, marginBottom: 12 }}>Add department</h2>
        <div className="form-field"><label htmlFor="department-org">Academic organization</label><select id="department-org" value={orgId} onChange={(e) => setOrgId(e.target.value)} required><option value="">Choose organization</option>{organizations.data?.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</select></div>
        <div className="form-field"><label htmlFor="department-code">Department code</label><input id="department-code" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} required /></div>
        <div className="form-field"><label htmlFor="department-name">Department name</label><input id="department-name" value={name} onChange={(e) => setName(e.target.value)} required /></div>
        {message && <p role="status" style={{ marginBottom: 10 }}>{message}</p>}<button className="btn btn-primary">Create department</button>
      </form>
      <SearchInput value={search} onChange={setSearch} label="Search departments" placeholder="Department name" />
      <DataTable
        columns={columns}
        rows={filtered}
        loading={departments.loading || organizations.loading}
        error={departments.error}
        onRetry={departments.refetch}
        emptyTitle="No departments found"
        emptyDescription="No departments match this search, or none have been created yet."
        rowKey={(d) => d.id}
      />
    </div>
  );
}
