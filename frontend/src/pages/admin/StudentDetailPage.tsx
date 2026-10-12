import { Link, useParams } from 'react-router-dom';
import { useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import type { StudentResponse, ProgramResponse, CurriculumResponse, StudentProgramResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { AccountAccessSection } from '../../components/ui/AccountAccessSection';

const PROGRAM_STATUSES = ['ACTIVE', 'ON_HOLD', 'COMPLETED', 'DISCONTINUED'] as const;
const STUDENT_STATUSES = ['ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'GRADUATED', 'WITHDRAWN'] as const;

function StudentProgramSection({ studentId, tenantId }: { studentId: string; tenantId: string }) {
  const studentPrograms = useApiQuery<StudentProgramResponse[]>(`/enrollment/student-programs?studentId=${studentId}`, [studentId]);
  const programs = useApiQuery<ProgramResponse[]>(`/catalog/programs?tenantId=${tenantId}`);
  const [programId, setProgramId] = useState('');
  const curricula = useApiQuery<CurriculumResponse[]>(
    programId ? `/catalog/curricula?programId=${programId}&tenantId=${tenantId}` : null, [programId],
  );
  const [curriculumId, setCurriculumId] = useState('');
  const [admissionYear, setAdmissionYear] = useState(String(new Date().getFullYear()));
  const [primary, setPrimary] = useState(true);
  const [startsAt, setStartsAt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState('');
  const [statusBusy, setStatusBusy] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');

  const programNameFor = (id: string) => programs.data?.find((p) => p.id === id)?.name ?? id;

  const assign = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setSubmitting(true); setMessage('');
    try {
      await apiFetch('/enrollment/student-programs', {
        method: 'POST',
        body: { studentId, programId, curriculumId, admissionYear: Number(admissionYear), primary, startsAt },
      });
      setMessage('Program assigned. The student can now request enrollment in that program\'s sections.');
      setProgramId(''); setCurriculumId(''); setStartsAt('');
      studentPrograms.refetch();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setMessage('This student already has an active program of this kind (primary or the college does not allow a second active program).');
      else setMessage(e instanceof Error ? e.message : 'Could not assign the program.');
    } finally { setSubmitting(false); }
  };

  const changeStatus = async (studentProgramId: string, status: typeof PROGRAM_STATUSES[number]) => {
    setStatusBusy(true); setStatusMessage('');
    try {
      await apiFetch(`/enrollment/student-programs/${studentProgramId}/status`, { method: 'POST', body: { status } });
      studentPrograms.refetch();
    } catch (e) { setStatusMessage(e instanceof Error ? e.message : 'Could not change program status.'); }
    finally { setStatusBusy(false); }
  };

  return (
    <section className="card" style={{ marginTop: 20 }}>
      <h2 style={{ fontSize: 17, marginBottom: 10 }}>Program &amp; curriculum</h2>
      <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>
        A student cannot request enrollment in any section until they have an active program assignment.
      </p>
      <div style={{ marginBottom: 16 }}>
        {studentPrograms.data && studentPrograms.data.length > 0 ? (
          studentPrograms.data.map((sp) => (
            <div key={sp.id} style={{ display: 'flex', gap: 12, alignItems: 'center', justifyContent: 'space-between', borderTop: '1px solid var(--line)', padding: '10px 0' }}>
              <span>
                <strong>{programNameFor(sp.programId)}</strong>{sp.primary ? ' · Primary' : ' · Secondary'}
                {' · '}<StatusBadge value={sp.status} />
              </span>
              {sp.status === 'ACTIVE' && (
                <div style={{ display: 'flex', gap: 6 }}>
                  <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} disabled={statusBusy}
                    onClick={() => changeStatus(sp.id, 'ON_HOLD')}>Put on hold</button>
                  <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} disabled={statusBusy}
                    onClick={() => changeStatus(sp.id, 'DISCONTINUED')}>Discontinue</button>
                </div>
              )}
              {sp.status === 'ON_HOLD' && (
                <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} disabled={statusBusy}
                  onClick={() => changeStatus(sp.id, 'ACTIVE')}>Reactivate</button>
              )}
            </div>
          ))
        ) : <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>No program assigned yet.</p>}
        {statusMessage && <p role="status" style={{ fontSize: 12, color: 'var(--flag)', marginTop: 8 }}>{statusMessage}</p>}
      </div>
      <form onSubmit={assign}>
        <div className="form-field">
          <label htmlFor="sp-program">Program</label>
          <select id="sp-program" value={programId} onChange={(e) => { setProgramId(e.target.value); setCurriculumId(''); }} required>
            <option value="">Choose program</option>
            {programs.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="form-field">
          <label htmlFor="sp-curriculum">Curriculum version</label>
          <select id="sp-curriculum" value={curriculumId} onChange={(e) => setCurriculumId(e.target.value)} required disabled={!programId}>
            <option value="">{programId ? 'Choose curriculum version' : 'Choose a program first'}</option>
            {curricula.data?.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.versionLabel})</option>)}
          </select>
          {programId && curricula.data && curricula.data.length === 0 && (
            <p style={{ fontSize: 12, color: 'var(--flag)', marginTop: 4 }}>
              This program has no curriculum versions yet — create one on the Programs page first.
            </p>
          )}
        </div>
        <div className="form-field">
          <label htmlFor="sp-year">Admission year</label>
          <input id="sp-year" type="number" min="2000" value={admissionYear} onChange={(e) => setAdmissionYear(e.target.value)} required />
        </div>
        <div className="form-field">
          <label htmlFor="sp-starts">Starts at</label>
          <input id="sp-starts" type="date" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
        </div>
        <label style={{ display: 'inline-flex', gap: 6, marginBottom: 12, fontSize: 13 }}>
          <input type="checkbox" checked={primary} onChange={(e) => setPrimary(e.target.checked)} />Primary program
        </label>
        {message && <p role="status" style={{ marginBottom: 10 }}>{message}</p>}
        <button type="submit" className="btn btn-primary" disabled={submitting || !curriculumId}>
          {submitting ? 'Assigning…' : 'Assign program'}
        </button>
      </form>
    </section>
  );
}

export default function StudentDetailPage() {
  const { studentId } = useParams<{ studentId: string }>();
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const { data, loading, error, refetch } = useApiQuery<StudentResponse>(studentId ? `/directory/students/${studentId}` : null);
  const guardians = useApiQuery<Array<{ id: string; studentId: string; guardianId: string; relationshipType: string | null; primary: boolean }>>(studentId ? `/directory/student-guardians?studentId=${studentId}` : null);
  const grants = useApiQuery<Array<{ grantId: string; studentId: string; guardianId: string; status: string; expiresAt: string | null; permissions: string[] }>>(studentId ? `/guardian-access/grants?studentId=${studentId}` : null);
  const [guardianId, setGuardianId] = useState('');
  const [newGuardian, setNewGuardian] = useState({ fullName: '', email: '', phone: '', dob: '', relationshipType: 'Parent', primary: false, emergencyContact: false });
  const [onboardingMessage, setOnboardingMessage] = useState('');
  const [onboardingBusy, setOnboardingBusy] = useState(false);
  const [expiresAt, setExpiresAt] = useState('');
  const [permissions, setPermissions] = useState<string[]>(['VIEW_ATTENDANCE', 'VIEW_GRADES']);
  const [grantMessage, setGrantMessage] = useState('');
  const [busy, setBusy] = useState(false);

  async function createGuardian(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!studentId) return;
    setOnboardingBusy(true); setOnboardingMessage('');
    try {
      await apiFetch('/admin/guardians', { method: 'POST', body: { ...newGuardian, studentId, dob: newGuardian.dob || null } });
      setOnboardingMessage('Guardian account created and linked. No academic access was granted.');
      setNewGuardian({ fullName: '', email: '', phone: '', dob: '', relationshipType: 'Parent', primary: false, emergencyContact: false });
      guardians.refetch();
    } catch (e) { setOnboardingMessage(e instanceof Error ? e.message : 'Could not create guardian account.'); }
    finally { setOnboardingBusy(false); }
  }

  async function createGrant(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!studentId) return;
    setBusy(true); setGrantMessage('');
    try {
      await apiFetch('/guardian-access/grants', { method: 'POST', body: { studentId, guardianId, expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null, permissions } });
      setGrantMessage('Scoped guardian access granted.'); grants.refetch();
    } catch (e) { setGrantMessage(e instanceof Error ? e.message : 'Could not create grant.'); }
    finally { setBusy(false); }
  }

  async function revokeGrant(id: string) {
    setBusy(true);
    try { await apiFetch(`/guardian-access/grants/${id}`, { method: 'DELETE', body: { reason: 'Revoked by college administrator' } }); grants.refetch(); }
    catch (e) { setGrantMessage(e instanceof Error ? e.message : 'Could not revoke grant.'); }
    finally { setBusy(false); }
  }

  const [statusBusy, setStatusBusy] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');
  const changeStatus = async (status: typeof STUDENT_STATUSES[number]) => {
    if (!studentId) return;
    setStatusBusy(true); setStatusMessage('');
    try {
      await apiFetch(`/directory/students/${studentId}/status`, { method: 'POST', body: { status, reason: `Set to ${status} by college administrator` } });
      refetch();
    } catch (e) { setStatusMessage(e instanceof Error ? e.message : 'Could not change status.'); }
    finally { setStatusBusy(false); }
  };

  return (
    <div>
      <p style={{ marginBottom: 16 }}>
        <Link to="/app/admin/students" style={{ fontSize: 13 }}>&larr; Back to students</Link>
      </p>
      {loading && <Skeleton height={120} />}
      {error && <div className="card"><ErrorState message="Could not load this student." onRetry={refetch} /></div>}
      {data && (
        <>
          <PageHeader title={data.fullName ?? 'Student'} subtitle={data.email ?? undefined} />
          <div className="card-grid">
            <div className="card">
              <div className="stat-label">Admission No.</div>
              <div className="mono" style={{ marginTop: 6, fontSize: 15 }}>{data.admissionNo}</div>
            </div>
            <div className="card">
              <div className="stat-label">Status</div>
              <div style={{ marginTop: 6 }}><StatusBadge value={data.status} /></div>
            </div>
          </div>
          <section className="card" style={{ marginTop: 20, maxWidth: 420 }}>
            <h2 style={{ fontSize: 16, marginBottom: 10 }}>Change status</h2>
            {statusMessage && <p role="status" style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 10 }}>{statusMessage}</p>}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {STUDENT_STATUSES.filter((s) => s !== data.status).map((s) => (
                <button key={s} type="button" className="btn btn-ghost" style={{ padding: '6px 12px', fontSize: 12 }} disabled={statusBusy}
                  onClick={() => changeStatus(s)}>{s.replaceAll('_', ' ')}</button>
              ))}
            </div>
          </section>
          {tenantId && <AccountAccessSection userId={data.userId} tenantId={tenantId} />}
          {tenantId && <StudentProgramSection studentId={data.id} tenantId={tenantId} />}
          <section className="card" style={{ marginTop: 20 }}>
            <h2 style={{ fontSize: 17, marginBottom: 10 }}>Add guardian account and relationship</h2>
            <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>Creating a relationship does not grant access to attendance, grades, finance, or other student information.</p>
            <form onSubmit={createGuardian}>
              {([['fullName','Guardian full name','text'],['email','Email','email'],['phone','Phone','tel'],['dob','Date of birth','date'],['relationshipType','Relationship to student','text']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`guardian-${key}`}>{label}</label><input id={`guardian-${key}`} type={type} value={newGuardian[key]} onChange={(e) => setNewGuardian({ ...newGuardian, [key]: e.target.value })} required={['fullName','email','relationshipType'].includes(key)} /></div>)}
              <fieldset style={{ border: 0, padding: 0, margin: '0 0 12px' }}><label style={{ display: 'inline-flex', gap: 6, marginRight: 14 }}><input type="checkbox" checked={newGuardian.primary} onChange={(e) => setNewGuardian({ ...newGuardian, primary: e.target.checked })} />Primary guardian</label><label style={{ display: 'inline-flex', gap: 6 }}><input type="checkbox" checked={newGuardian.emergencyContact} onChange={(e) => setNewGuardian({ ...newGuardian, emergencyContact: e.target.checked })} />Emergency contact</label></fieldset>
              {onboardingMessage && <p role="status" style={{ marginBottom: 10 }}>{onboardingMessage}</p>}<button type="submit" className="btn btn-primary" disabled={onboardingBusy}>{onboardingBusy ? 'Creating…' : 'Create and link guardian'}</button>
            </form>
          </section>
          <section className="card" style={{ marginTop: 20 }}>
            <h2 style={{ fontSize: 17, marginBottom: 10 }}>Guardian access</h2>
            <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>Access requires an existing guardian relationship. Permissions are explicit and can be revoked immediately.</p>
            <form onSubmit={createGrant}>
              <div className="form-field"><label htmlFor="linked-guardian">Linked guardian</label><select id="linked-guardian" value={guardianId} onChange={(e) => setGuardianId(e.target.value)} required><option value="">Choose guardian</option>{guardians.data?.map((g) => <option key={g.id} value={g.guardianId}>{g.guardianId}{g.primary ? ' · Primary' : ''}</option>)}</select></div>
              <div className="form-field"><label htmlFor="grant-expiry">Expires (optional)</label><input id="grant-expiry" type="datetime-local" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} /></div>
              <fieldset style={{ border: 0, padding: 0, margin: '0 0 12px' }}><legend style={{ fontSize: 13, marginBottom: 6 }}>Allowed information</legend>{['VIEW_ATTENDANCE','VIEW_GRADES','VIEW_SCHEDULE','VIEW_ACADEMIC_STATUS','VIEW_LEARNING_DATA','VIEW_FINANCE','VIEW_RISK_SCORE'].map((p) => <label key={p} style={{ display: 'inline-flex', gap: 6, margin: '4px 12px 4px 0', fontSize: 13 }}><input type="checkbox" checked={permissions.includes(p)} onChange={(e) => setPermissions(e.target.checked ? [...permissions, p] : permissions.filter((x) => x !== p))} />{p.replaceAll('_',' ').toLowerCase()}</label>)}</fieldset>
              {grantMessage && <p role="status" style={{ marginBottom: 10 }}>{grantMessage}</p>}<button className="btn btn-primary" disabled={busy || !permissions.length}>Grant access</button>
            </form>
            <div style={{ marginTop: 16 }}>{grants.data?.map((g) => <div key={g.grantId} style={{ display: 'flex', gap: 12, alignItems: 'center', justifyContent: 'space-between', borderTop: '1px solid var(--line)', padding: '10px 0' }}><span><strong>{g.status}</strong> · {g.guardianId}<br /><small>{g.permissions.join(', ') || 'No permissions'}{g.expiresAt ? ` · expires ${new Date(g.expiresAt).toLocaleDateString()}` : ''}</small></span>{g.status === 'ACTIVE' && <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => revokeGrant(g.grantId)}>Revoke</button>}</div>)}</div>
          </section>
        </>
      )}
    </div>
  );
}
