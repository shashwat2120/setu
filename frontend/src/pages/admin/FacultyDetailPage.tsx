import { Link, useParams } from 'react-router-dom';
import { useState } from 'react';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { FacultyResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { AccountAccessSection } from '../../components/ui/AccountAccessSection';

const FACULTY_STATUSES = ['ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'TERMINATED'] as const;

export default function FacultyDetailPage() {
  const { facultyId } = useParams<{ facultyId: string }>();
  const { data, loading, error, refetch } = useApiQuery<FacultyResponse>(facultyId ? `/directory/faculty/${facultyId}` : null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const changeStatus = async (status: typeof FACULTY_STATUSES[number]) => {
    if (!facultyId) return;
    setBusy(true); setMessage('');
    try {
      await apiFetch(`/directory/faculty/${facultyId}/status`, { method: 'POST', body: { status } });
      refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not change status.'); }
    finally { setBusy(false); }
  };

  return (
    <div>
      <p style={{ marginBottom: 16 }}>
        <Link to="/app/admin/faculty" style={{ fontSize: 13 }}>&larr; Back to faculty</Link>
      </p>
      {loading && <Skeleton height={120} />}
      {error && <div className="card"><ErrorState message="Could not load this faculty member." onRetry={refetch} /></div>}
      {data && (
        <>
          <PageHeader title={data.fullName ?? 'Faculty'} subtitle={data.email ?? undefined} />
          <div className="card-grid">
            <div className="card">
              <div className="stat-label">Employee No.</div>
              <div className="mono" style={{ marginTop: 6, fontSize: 15 }}>{data.employeeNo}</div>
            </div>
            <div className="card">
              <div className="stat-label">Status</div>
              <div style={{ marginTop: 6 }}><StatusBadge value={data.status} /></div>
            </div>
            {data.userId && (
              <div className="card">
                <div className="stat-label">User ID</div>
                <div className="mono" style={{ marginTop: 6, fontSize: 12 }}>{data.userId}</div>
                <p style={{ fontSize: 11, color: 'var(--ink-soft)', marginTop: 4 }}>Needed to assign this person as a department HOD or a platform role.</p>
              </div>
            )}
          </div>
          <section className="card" style={{ marginTop: 20, maxWidth: 420 }}>
            <h2 style={{ fontSize: 16, marginBottom: 10 }}>Change status</h2>
            {message && <p role="status" style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 10 }}>{message}</p>}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {FACULTY_STATUSES.filter((s) => s !== data.status).map((s) => (
                <button key={s} type="button" className="btn btn-ghost" style={{ padding: '6px 12px', fontSize: 12 }} disabled={busy}
                  onClick={() => changeStatus(s)}>{s.replaceAll('_', ' ')}</button>
              ))}
            </div>
          </section>
          <AccountAccessSection userId={data.userId} tenantId={data.tenantId} />
        </>
      )}
    </div>
  );
}
