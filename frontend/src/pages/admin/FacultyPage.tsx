import { useMemo, useState } from 'react';
import { apiFetch } from '../../api/client';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { FacultyResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';
import { SearchInput } from '../../components/ui/SearchInput';
import { StatusBadge } from '../../components/ui/StatusBadge';

export default function FacultyPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState({ fullName: '', email: '', phone: '', employeeNo: '', designation: '', dob: '', joinedAt: '' });
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);

  const { data, loading, error, refetch, page, setPage } = usePagedQuery<FacultyResponse>(
    tenantId ? '/directory/faculty' : null,
    tenantId ? { tenantId } : {},
  );

  const filtered = useMemo(() => {
    if (!data) return null;
    if (!search.trim()) return data.content;
    const q = search.toLowerCase();
    return data.content.filter(
      (f) => f.fullName?.toLowerCase().includes(q) || f.email?.toLowerCase().includes(q) || f.employeeNo.toLowerCase().includes(q),
    );
  }, [data, search]);

  const columns: Column<FacultyResponse>[] = [
    { key: 'name', header: 'Name', render: (f) => f.fullName ?? '—' },
    { key: 'employeeNo', header: 'Employee No.', render: (f) => <span className="mono">{f.employeeNo}</span> },
    { key: 'email', header: 'Email', render: (f) => f.email ?? '—' },
    { key: 'status', header: 'Status', render: (f) => <StatusBadge value={f.status} /> },
  ];

  async function onboard(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!tenantId) return;
    setSaving(true); setMessage('');
    try {
      await apiFetch('/admin/faculty', { method: 'POST', body: { ...draft, tenantId, dob: draft.dob || null, joinedAt: draft.joinedAt || null } });
      setDraft({ fullName: '', email: '', phone: '', employeeNo: '', designation: '', dob: '', joinedAt: '' }); setMessage('Faculty account created. A password setup email was requested; role assignment required a fresh passkey check.'); refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not onboard faculty.'); }
    finally { setSaving(false); }
  }

  return (
    <div>
      <PageHeader title="Faculty" subtitle={data ? `${data.totalElements} on staff` : undefined} />
      <form className="card" onSubmit={onboard} style={{ marginBottom: 18 }}>
        <h2 style={{ fontSize: 17, marginBottom: 12 }}>Onboard faculty</h2>
        {([['fullName','Full name','text'],['email','Email','email'],['phone','Phone','tel'],['employeeNo','Employee number','text'],['designation','Designation','text'],['dob','Date of birth','date'],['joinedAt','Start date','date']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`faculty-${key}`}>{label}</label><input id={`faculty-${key}`} type={type} value={draft[key]} onChange={(e) => setDraft({ ...draft, [key]: e.target.value })} required={['fullName','email','employeeNo','designation','joinedAt'].includes(key)} /></div>)}
        {message && <p role="status" style={{ marginBottom: 10 }}>{message}</p>}<button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Creating…' : 'Create faculty account'}</button>
      </form>
      <SearchInput value={search} onChange={setSearch} label="Search faculty" placeholder="Name, email, or employee number" />
      <DataTable
        columns={columns}
        rows={filtered}
        loading={loading}
        error={error}
        onRetry={refetch}
        emptyTitle="No faculty found"
        emptyDescription="No faculty match this search, or none have been added yet."
        rowKey={(f) => f.id}
        onRowClick={(f) => navigate(`/app/admin/faculty/${f.id}`)}
      />
      {data && <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />}
    </div>
  );
}
