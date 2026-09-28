import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { PageResponse, StudentResponse, FacultyResponse, CourseResponse, ProgramResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/EmptyState';

function OverviewCard({ label, to, loading, count }: { label: string; to: string; loading: boolean; count: number | undefined }) {
  return (
    <Link to={to} className="card" style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>
      {loading ? <Skeleton height={32} width={60} /> : <div className="stat-value">{count ?? '—'}</div>}
      <div className="stat-label">{label}</div>
    </Link>
  );
}

export default function AdminDashboard() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');

  const students = useApiQuery<PageResponse<StudentResponse>>(
    tenantId ? `/directory/students?tenantId=${tenantId}&size=1` : null,
  );
  const faculty = useApiQuery<PageResponse<FacultyResponse>>(
    tenantId ? `/directory/faculty?tenantId=${tenantId}&size=1` : null,
  );
  const courses = useApiQuery<PageResponse<CourseResponse>>(
    tenantId ? `/catalog/courses?tenantId=${tenantId}&size=1` : null,
  );
  const programs = useApiQuery<ProgramResponse[]>(tenantId ? `/catalog/programs?tenantId=${tenantId}` : null);

  return (
    <div>
      <PageHeader eyebrow="SETU · COLLEGE ADMIN" title="Registrar overview" subtitle={user?.email ?? undefined} />
      <div className="card-grid">
        <OverviewCard label="Students" to="/app/admin/students" loading={students.loading} count={students.data?.totalElements} />
        <OverviewCard label="Faculty" to="/app/admin/faculty" loading={faculty.loading} count={faculty.data?.totalElements} />
        <OverviewCard label="Courses" to="/app/admin/courses" loading={courses.loading} count={courses.data?.totalElements} />
        <OverviewCard label="Programs" to="/app/admin/programs" loading={programs.loading} count={programs.data?.length} />
      </div>
    </div>
  );
}
