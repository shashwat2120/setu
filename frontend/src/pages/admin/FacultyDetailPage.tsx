import { Link, useParams } from 'react-router-dom';
import { useApiQuery } from '../../api/useApiQuery';
import type { FacultyResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';

export default function FacultyDetailPage() {
  const { facultyId } = useParams<{ facultyId: string }>();
  const { data, loading, error, refetch } = useApiQuery<FacultyResponse>(facultyId ? `/directory/faculty/${facultyId}` : null);

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
          </div>
        </>
      )}
    </div>
  );
}
