import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { useApiQuery } from '../../api/useApiQuery';
import type { PageResponse, EnrollmentResponse, RiskScoreResponse, StudentAccountResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';

function OverviewCard({ label, to, loading, children }: { label: string; to: string; loading: boolean; children: ReactNode }) {
  return (
    <Link to={to} className="card" style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>
      {loading ? <Skeleton height={32} width={60} /> : children}
      <div className="stat-label" style={{ marginTop: 6 }}>{label}</div>
    </Link>
  );
}

export default function StudentDashboard() {
  const { user } = useAuth();
  const studentId = user?.studentId ?? null;

  const enrollments = useApiQuery<PageResponse<EnrollmentResponse>>(studentId ? `/enrollment/enrollments?studentId=${studentId}&size=1` : null);
  const risk = useApiQuery<RiskScoreResponse>(studentId ? `/risk/scores/current?studentId=${studentId}` : null);
  const tenantId = user?.roles.find((r) => r.roleCode === 'STUDENT')?.tenantId ?? null;
  const account = useApiQuery<StudentAccountResponse>(
    tenantId && studentId ? `/finance/accounts?tenantId=${tenantId}&studentId=${studentId}` : null,
  );

  return (
    <div>
      <PageHeader eyebrow="SETU · STUDENT" title={`Welcome, ${user?.fullName ?? 'Student'}`} subtitle="Your academic snapshot" />

      <div className="card-grid" style={{ marginBottom: 24 }}>
        <OverviewCard label="Enrolled sections" to="/app/student/courses" loading={enrollments.loading}>
          <div className="stat-value">{enrollments.data?.totalElements ?? '—'}</div>
        </OverviewCard>
        <OverviewCard label="Risk band" to="/app/student/success" loading={risk.loading}>
          {risk.data ? <StatusBadge value={risk.data.band} /> : <div style={{ fontSize: 15, color: 'var(--ink-soft)' }}>Not yet calculated</div>}
        </OverviewCard>
        <OverviewCard label="Account balance" to="/app/student/finance" loading={account.loading}>
          <div className="stat-value" style={{ fontSize: 20 }}>{account.data ? `${account.data.balance} ${account.data.currency}` : '—'}</div>
        </OverviewCard>
        <OverviewCard label="AI Advisor" to="/app/student/advisor" loading={false}>
          <div style={{ fontSize: 15 }}>Ask about your progress</div>
        </OverviewCard>
      </div>
    </div>
  );
}
