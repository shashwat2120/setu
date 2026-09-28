import { useMemo, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { PlacementRecordResponse, StudentResponse, PageResponse, JobResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { SearchInput } from '../../components/ui/SearchInput';

const inputStyle: React.CSSProperties = {
  width: '100%', fontFamily: 'var(--font-body)', fontSize: 14, padding: '9px 12px',
  border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)',
};
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 };

export default function PlacementPlacementsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'PLACEMENT_OFFICER');

  const records = useApiQuery<PlacementRecordResponse[]>(tenantId ? `/career/placement-records?tenantId=${tenantId}` : null);
  const students = useApiQuery<PageResponse<StudentResponse>>(tenantId ? `/directory/students?tenantId=${tenantId}&size=500` : null);
  const jobs = useApiQuery<JobResponse[]>(tenantId ? `/career/jobs?tenantId=${tenantId}` : null);
  const studentNameFor = (studentId: string) =>
    students.data?.content.find((s) => s.id === studentId)?.fullName
    ?? students.data?.content.find((s) => s.id === studentId)?.email
    ?? studentId;

  const [search, setSearch] = useState('');
  const [selectedStudent, setSelectedStudent] = useState<{ id: string; name: string } | null>(null);
  const [jobId, setJobId] = useState('');
  const [employer, setEmployer] = useState('');
  const [role, setRole] = useState('');
  const [ctc, setCtc] = useState('');
  const [placedAt, setPlacedAt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const filteredStudents = useMemo(() => {
    if (!students.data || !search.trim()) return [];
    const q = search.toLowerCase();
    return students.data.content.filter((s) => s.fullName?.toLowerCase().includes(q) || s.email?.toLowerCase().includes(q)).slice(0, 8);
  }, [students.data, search]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedStudent) { setError('Select a student first.'); return; }
    setSubmitting(true);
    setError(null);
    setSuccess(false);
    try {
      await apiFetch('/career/placement-records', {
        method: 'POST',
        body: { studentId: selectedStudent.id, jobId: jobId || null, employer, role, ctc: Number(ctc), placedAt },
      });
      setEmployer(''); setRole(''); setCtc(''); setPlacedAt(''); setJobId(''); setSelectedStudent(null);
      setSuccess(true);
      records.refetch();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not record this placement.');
    } finally {
      setSubmitting(false);
    }
  };

  const columns: Column<PlacementRecordResponse>[] = [
    { key: 'student', header: 'Student', render: (r) => studentNameFor(r.studentId) },
    { key: 'employer', header: 'Employer', render: (r) => r.employer },
    { key: 'role', header: 'Role', render: (r) => r.role },
    { key: 'ctc', header: 'CTC', render: (r) => <span className="mono">{r.ctc}</span> },
    { key: 'placedAt', header: 'Placed', render: (r) => r.placedAt },
  ];

  return (
    <div>
      <PageHeader title="Placements" subtitle="Record a placement outcome and review the placement history." />

      <form onSubmit={submit} className="card" style={{ marginBottom: 24, maxWidth: 480 }}>
        <h2 style={{ fontSize: 16, marginBottom: 14 }}>Record a placement</h2>
        <div style={{ marginBottom: 12 }}>
          <label style={labelStyle}>Student</label>
          {selectedStudent ? (
            <p style={{ fontSize: 14 }}>
              <strong>{selectedStudent.name}</strong>{' '}
              <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12, marginLeft: 8 }}
                onClick={() => setSelectedStudent(null)}>Change</button>
            </p>
          ) : (
            <>
              <SearchInput value={search} onChange={setSearch} label="" placeholder="Search by name or email" />
              {filteredStudents.length > 0 && (
                <div className="card" style={{ padding: 8, marginTop: -8 }}>
                  {filteredStudents.map((s) => (
                    <button key={s.id} type="button" className="btn btn-ghost" style={{ display: 'block', width: '100%', textAlign: 'left', marginBottom: 4 }}
                      onClick={() => { setSelectedStudent({ id: s.id, name: s.fullName ?? s.email ?? s.id }); setSearch(''); }}>
                      {s.fullName ?? s.email}
                    </button>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
        <div style={{ marginBottom: 12 }}>
          <label htmlFor="rec-job" style={labelStyle}>Job (optional)</label>
          <select id="rec-job" value={jobId} onChange={(e) => setJobId(e.target.value)} style={inputStyle}>
            <option value="">Not linked to a job listing</option>
            {jobs.data?.map((j) => <option key={j.id} value={j.id}>{j.title} — {j.employer}</option>)}
          </select>
        </div>
        <div style={{ marginBottom: 12 }}>
          <label htmlFor="rec-employer" style={labelStyle}>Employer</label>
          <input id="rec-employer" required value={employer} onChange={(e) => setEmployer(e.target.value)} style={inputStyle} />
        </div>
        <div style={{ marginBottom: 12 }}>
          <label htmlFor="rec-role" style={labelStyle}>Role</label>
          <input id="rec-role" required value={role} onChange={(e) => setRole(e.target.value)} style={inputStyle} />
        </div>
        <div style={{ marginBottom: 12 }}>
          <label htmlFor="rec-ctc" style={labelStyle}>CTC</label>
          <input id="rec-ctc" required type="number" min="0" value={ctc} onChange={(e) => setCtc(e.target.value)} style={inputStyle} />
        </div>
        <div style={{ marginBottom: 16 }}>
          <label htmlFor="rec-date" style={labelStyle}>Placed on</label>
          <input id="rec-date" required type="date" value={placedAt} onChange={(e) => setPlacedAt(e.target.value)} style={inputStyle} />
        </div>
        {error && <p style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{error}</p>}
        {success && <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>Placement recorded.</p>}
        <button type="submit" className="btn btn-primary" disabled={submitting}>{submitting ? 'Recording…' : 'Record placement'}</button>
      </form>

      <DataTable
        columns={columns}
        rows={records.data}
        loading={records.loading || students.loading}
        error={records.error}
        onRetry={records.refetch}
        emptyTitle="No placements recorded"
        emptyDescription="No placement records exist yet for this college."
        rowKey={(r) => r.id}
      />
    </div>
  );
}
