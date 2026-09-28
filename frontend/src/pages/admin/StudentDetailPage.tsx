import { Link, useParams } from 'react-router-dom';
import { useState } from 'react';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { StudentResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';

export default function StudentDetailPage() {
  const { studentId } = useParams<{ studentId: string }>();
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
