import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { StudentResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';
import { SearchInput } from '../../components/ui/SearchInput';
import { StatusBadge } from '../../components/ui/StatusBadge';

const inputStyle: React.CSSProperties = {
  width: '100%', fontFamily: 'var(--font-body)', fontSize: 14, padding: '9px 12px',
  border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)',
};
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 };

function AddStudentForm({ tenantId, onAdmitted }: { tenantId: string; onAdmitted: () => void }) {
  const [open, setOpen] = useState(false);
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [dob, setDob] = useState('');
  const [admissionNo, setAdmissionNo] = useState('');
  const [rollNo, setRollNo] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setSuccess(false);
    try {
      await apiFetch('/admin/students', {
        method: 'POST',
        body: {
          tenantId,
          fullName,
          preferredName: fullName.split(' ')[0],
          dob,
          gender: 'UNSPECIFIED',
          email,
          phone,
          admissionNo,
          rollNo,
          admissionDate: new Date().toISOString().slice(0, 10),
        },
      });
      setFullName(''); setEmail(''); setPhone(''); setDob(''); setAdmissionNo(''); setRollNo('');
      setSuccess(true);
      onAdmitted();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not admit this student.');
    } finally {
      setSubmitting(false);
    }
  };

  if (!open) {
    return (
      <button type="button" className="btn btn-primary" style={{ marginBottom: 20 }} onClick={() => setOpen(true)}>
        Add student
      </button>
    );
  }

  return (
    <form onSubmit={submit} className="card" style={{ marginBottom: 24, maxWidth: 520 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
        <h2 style={{ fontSize: 16 }}>Admit a new student</h2>
        <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => setOpen(false)}>Cancel</button>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
        <div>
          <label htmlFor="stu-name" style={labelStyle}>Full name</label>
          <input id="stu-name" required value={fullName} onChange={(e) => setFullName(e.target.value)} style={inputStyle} placeholder="e.g. Kiran Shah" />
        </div>
        <div>
          <label htmlFor="stu-email" style={labelStyle}>Email</label>
          <input id="stu-email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} style={inputStyle} placeholder="student@example.com" />
        </div>
        <div>
          <label htmlFor="stu-phone" style={labelStyle}>Phone</label>
          <input id="stu-phone" required value={phone} onChange={(e) => setPhone(e.target.value)} style={inputStyle} placeholder="+91-9000000000" />
        </div>
        <div>
          <label htmlFor="stu-dob" style={labelStyle}>Date of birth</label>
          <input id="stu-dob" type="date" required value={dob} onChange={(e) => setDob(e.target.value)} style={inputStyle} />
        </div>
        <div>
          <label htmlFor="stu-admno" style={labelStyle}>Admission No.</label>
          <input id="stu-admno" required value={admissionNo} onChange={(e) => setAdmissionNo(e.target.value)} style={inputStyle} placeholder="ADM-2025-0042" />
        </div>
        <div>
          <label htmlFor="stu-roll" style={labelStyle}>Roll No.</label>
          <input id="stu-roll" required value={rollNo} onChange={(e) => setRollNo(e.target.value)} style={inputStyle} placeholder="ROLL-2025-0042" />
        </div>
      </div>
      <p style={{ fontSize: 12.5, color: 'var(--ink-soft)', marginBottom: 12 }}>
        Creates the account, admits the student, and emails a password-reset link so they can sign in.
      </p>
      {error && <p style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{error}</p>}
      {success && <p style={{ color: 'var(--verified)', fontSize: 13, marginBottom: 12 }}>Student admitted.</p>}
      <button type="submit" className="btn btn-primary" disabled={submitting}>{submitting ? 'Admitting…' : 'Admit student'}</button>
    </form>
  );
}

export default function StudentsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const navigate = useNavigate();
  const [search, setSearch] = useState('');

  const { data, loading, error, refetch, page, setPage } = usePagedQuery<StudentResponse>(
    tenantId ? '/directory/students' : null,
    tenantId ? { tenantId } : {},
  );

  const filtered = useMemo(() => {
    if (!data) return null;
    if (!search.trim()) return data.content;
    const q = search.toLowerCase();
    return data.content.filter(
      (s) => s.fullName?.toLowerCase().includes(q) || s.email?.toLowerCase().includes(q) || s.admissionNo.toLowerCase().includes(q),
    );
  }, [data, search]);

  const columns: Column<StudentResponse>[] = [
    { key: 'name', header: 'Name', render: (s) => s.fullName ?? '—' },
    { key: 'admissionNo', header: 'Admission No.', render: (s) => <span className="mono">{s.admissionNo}</span> },
    { key: 'email', header: 'Email', render: (s) => s.email ?? '—' },
    { key: 'status', header: 'Status', render: (s) => <StatusBadge value={s.status} /> },
  ];

  return (
    <div>
      <PageHeader title="Students" subtitle={data ? `${data.totalElements} enrolled` : undefined} />
      {tenantId && <AddStudentForm tenantId={tenantId} onAdmitted={refetch} />}
      <SearchInput value={search} onChange={setSearch} label="Search students" placeholder="Name, email, or admission number" />
      <DataTable
        columns={columns}
        rows={filtered}
        loading={loading}
        error={error}
        onRetry={refetch}
        emptyTitle="No students found"
        emptyDescription="No students match this search, or none have been admitted yet."
        rowKey={(s) => s.id}
        onRowClick={(s) => navigate(`/app/admin/students/${s.id}`)}
      />
      {data && <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />}
    </div>
  );
}
