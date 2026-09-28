import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { JobResponse, SkillResponse, PlacementRecordResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/EmptyState';

function OverviewCard({ label, to, loading, value }: { label: string; to: string; loading: boolean; value: number | undefined }) {
  return (
    <Link to={to} className="card" style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>
      {loading ? <Skeleton height={32} width={60} /> : <div className="stat-value">{value ?? '—'}</div>}
      <div className="stat-label">{label}</div>
    </Link>
  );
}

export default function PlacementDashboard() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'PLACEMENT_OFFICER');

  const jobs = useApiQuery<JobResponse[]>(tenantId ? `/career/jobs?tenantId=${tenantId}` : null);
  const skills = useApiQuery<SkillResponse[]>(tenantId ? `/career/skills?tenantId=${tenantId}` : null);
  const placements = useApiQuery<PlacementRecordResponse[]>(tenantId ? `/career/placement-records?tenantId=${tenantId}` : null);

  const openJobs = jobs.data?.filter((j) => j.status === 'OPEN').length;

  return (
    <div>
      <PageHeader eyebrow="SETU · PLACEMENT" title={`Welcome, ${user?.fullName ?? 'Placement Officer'}`} subtitle="Jobs, skills, and placement outcomes." />
      <div className="card-grid">
        <OverviewCard label="Open jobs" to="/app/placement/jobs" loading={jobs.loading} value={openJobs} />
        <OverviewCard label="Total jobs posted" to="/app/placement/jobs" loading={jobs.loading} value={jobs.data?.length} />
        <OverviewCard label="Skills catalogued" to="/app/placement/skills" loading={skills.loading} value={skills.data?.length} />
        <OverviewCard label="Placements recorded" to="/app/placement/placements" loading={placements.loading} value={placements.data?.length} />
      </div>
    </div>
  );
}
