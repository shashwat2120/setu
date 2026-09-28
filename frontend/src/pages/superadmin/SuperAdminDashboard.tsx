import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { useApiQuery } from '../../api/useApiQuery';
import type { CollegeResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/EmptyState';

function OverviewCard({ label, to, loading, value }: { label: string; to: string; loading: boolean; value: string | number | undefined }) {
  return (
    <Link to={to} className="card" style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>
      {loading ? <Skeleton height={32} width={60} /> : <div className="stat-value">{value ?? '—'}</div>}
      <div className="stat-label">{label}</div>
    </Link>
  );
}

export default function SuperAdminDashboard() {
  const { user } = useAuth();
  const colleges = useApiQuery<CollegeResponse[]>('/institution/colleges');

  const active = colleges.data?.filter((c) => c.status === 'ACTIVE').length;
  const suspended = colleges.data?.filter((c) => c.status === 'SUSPENDED').length;

  return (
    <div>
      <PageHeader eyebrow="SETU · SUPER ADMIN" title="Platform overview" subtitle={`Signed in as ${user?.email ?? 'platform admin'} · SUPER_ADMIN`} />
      <div className="card-grid">
        <OverviewCard label="Colleges" to="/app/super-admin/colleges" loading={colleges.loading} value={colleges.data?.length} />
        <OverviewCard label="Active" to="/app/super-admin/colleges" loading={colleges.loading} value={active} />
        <OverviewCard label="Suspended" to="/app/super-admin/colleges" loading={colleges.loading} value={suspended} />
        <OverviewCard label="Audit log" to="/app/super-admin/audit-log" loading={false} value="View" />
        <OverviewCard label="Roles" to="/app/super-admin/roles" loading={false} value="Manage" />
      </div>
    </div>
  );
}
