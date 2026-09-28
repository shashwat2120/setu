import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { FacultyAssignmentResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton, EmptyState, ErrorState } from '../../components/ui/EmptyState';

export default function FacultyDashboard() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;

  const { data: assignments, loading, error, refetch } = useApiQuery<FacultyAssignmentResponse[]>(
    facultyId && tenantId ? `/enrollment/faculty-assignments?facultyId=${facultyId}&tenantId=${tenantId}` : null,
  );
  const activeAssignments = assignments?.filter((a) => a.active) ?? [];

  return (
    <div>
      <PageHeader eyebrow="SETU · FACULTY" title={`Welcome, ${user?.fullName ?? 'Faculty'}`} subtitle="Your assigned sections" />
      <div className="card-grid" style={{ marginBottom: 24 }}>
        <div className="card">
          {loading ? <Skeleton height={32} width={40} /> : <div className="stat-value">{activeAssignments.length}</div>}
          <div className="stat-label">Active sections</div>
        </div>
      </div>

      <section className="card">
        <h2 style={{ fontSize: 16, marginBottom: 12 }}>Your sections</h2>
        {loading && <Skeleton height={60} />}
        {error && <ErrorState message="Could not load your assigned sections." onRetry={refetch} />}
        {assignments && activeAssignments.length === 0 && (
          <EmptyState title="No sections assigned" description="You are not currently assigned to any section." />
        )}
        {activeAssignments.length > 0 && (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {activeAssignments.map((a) => (
              <li key={a.id}>
                <Link to="/app/faculty/sections" style={{ fontWeight: 600 }}>
                  Section {a.sectionId.slice(0, 8)}… — {a.role}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
