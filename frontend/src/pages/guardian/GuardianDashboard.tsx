import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { GuardianGrantResponse, StudentResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { EmptyState, Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';

const PERMISSION_LABELS: Record<string, string> = {
  VIEW_GRADES: 'Grades',
  VIEW_ATTENDANCE: 'Attendance',
  VIEW_SCHEDULE: 'Schedule',
  VIEW_ACADEMIC_STATUS: 'Academic status',
  VIEW_LEARNING_DATA: 'Learning progress',
  VIEW_FINANCE: 'Finance',
  VIEW_RISK_SCORE: 'Risk score',
};

function StudentName({ studentId, canView }: { studentId: string; canView: boolean }) {
  const profile = useApiQuery<StudentResponse>(canView ? `/directory/students/${studentId}` : null);
  if (!canView) {
    return <span style={{ color: 'var(--ink-soft)' }}>Student {studentId.slice(0, 8)}… <span style={{ fontStyle: 'italic' }}>(name requires an academic-status grant)</span></span>;
  }
  if (profile.loading) return <Skeleton height={18} width={140} />;
  return <strong>{profile.data?.fullName ?? `Student ${studentId.slice(0, 8)}…`}</strong>;
}

export default function GuardianDashboard() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'GUARDIAN');
  const grants = useApiQuery<GuardianGrantResponse[]>(tenantId ? `/guardian-access/grants/mine?tenantId=${tenantId}` : null);

  return (
    <div>
      <PageHeader eyebrow="SETU · GUARDIAN" title={`Welcome, ${user?.fullName ?? 'Guardian'}`} subtitle="Delegated student access" />
      <p style={{ marginBottom: 20, fontSize: 13, color: 'var(--ink-soft)' }}>
        A guardian relationship alone never grants access. Every row below reflects only an explicit, currently
        active grant &mdash; scoped to exactly the data categories it lists. A revoked or expired grant, or a
        relationship with no grant at all, shows here too, but leads nowhere.
      </p>

      {!tenantId ? (
        <EmptyState title="No guardian profile linked" description="Your account has a GUARDIAN role but no linked directory profile yet." />
      ) : grants.loading ? (
        <Skeleton height={100} />
      ) : grants.error ? (
        <div className="card"><ErrorState message="Could not load your access grants." onRetry={grants.refetch} /></div>
      ) : !grants.data || grants.data.length === 0 ? (
        <EmptyState title="No access grants" description="No student access has been granted to you yet. Contact your college admin." />
      ) : (
        <div className="card-grid">
          {grants.data.map((g) => (
            <div key={g.grantId} className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 }}>
                <StudentName studentId={g.studentId} canView={g.status === 'ACTIVE' && g.permissions.includes('VIEW_ACADEMIC_STATUS')} />
                <StatusBadge value={g.status} />
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: g.status === 'ACTIVE' ? 12 : 0 }}>
                {g.permissions.length === 0 ? (
                  <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>No permissions on this grant</span>
                ) : g.permissions.map((p) => (
                  <span key={p} className="mono" style={{
                    fontSize: 11, padding: '3px 8px', borderRadius: 3, background: 'var(--paper-warm)',
                    border: '1px solid var(--rule)',
                  }}>
                    {PERMISSION_LABELS[p] ?? p}
                  </span>
                ))}
              </div>
              {g.expiresAt && (
                <div style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: g.status === 'ACTIVE' ? 12 : 0 }}>
                  Expires {new Date(g.expiresAt).toLocaleString()}
                </div>
              )}
              {g.status === 'ACTIVE' && (
                <Link to={`/app/guardian/students/${g.studentId}`} className="btn btn-primary" style={{ padding: '6px 12px', fontSize: 12 }}>
                  View student
                </Link>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
