import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { PlacementStatsResponse, ProgramResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Skeleton } from '../../components/ui/EmptyState';
import { plainNumber } from '../../lib/jobTerms';

const lpa = (value: number | null | undefined) => (value == null ? 'Not recorded' : `₹${plainNumber(value)} LPA`);
const percent = (value: number | null | undefined) => (value == null ? 'No students' : `${plainNumber(value)}%`);

function Figure({ label, value, loading }: { label: string; value: string; loading: boolean }) {
  return (
    <div className="card">
      {loading ? <Skeleton height={32} width={90} /> : <div className="stat-value">{value}</div>}
      <div className="stat-label">{label}</div>
    </div>
  );
}

/** How the college is doing: who is placed, in which programs, at what package, with whom, and where applications stand. */
export default function PlacementStatisticsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'PLACEMENT_OFFICER');
  const stats = useApiQuery<PlacementStatsResponse>(tenantId ? `/career/placement-stats?tenantId=${tenantId}` : null);
  const programs = useApiQuery<ProgramResponse[]>(tenantId ? `/catalog/programs?tenantId=${tenantId}` : null);

  const s = stats.data;
  const programName = (id: string) => programs.data?.find((p) => p.id === id)?.name ?? 'A program';

  const programColumns: Column<PlacementStatsResponse['placements']['byProgram'][number]>[] = [
    { key: 'program', header: 'Program', render: (r) => programName(r.programId) },
    { key: 'students', header: 'Students', render: (r) => r.students },
    { key: 'placed', header: 'Placed', render: (r) => r.placed },
    { key: 'pct', header: 'Placed', render: (r) => percent(r.placedPercentage) },
  ];
  const recruiterColumns: Column<PlacementStatsResponse['placements']['topRecruiters'][number]>[] = [
    { key: 'employer', header: 'Employer', render: (r) => r.employer },
    { key: 'placements', header: 'Placements', render: (r) => r.placements },
    { key: 'avg', header: 'Average package', render: (r) => lpa(r.averageLpa) },
  ];

  return (
    <div>
      <PageHeader title="Placement statistics" subtitle="Who is placed, in which programs, at what package, and where applications stand." />
      {stats.error && !stats.loading && <div className="card" role="alert"><p>Could not load the statistics. <button type="button" className="btn btn-ghost" onClick={stats.refetch}>Try again</button></p></div>}

      <div className="card-grid">
        <Figure label="Students placed" value={s ? String(s.placements.placedStudents) : ''} loading={stats.loading} />
        <Figure label="Placed, of students in a program" value={s ? percent(s.placements.placedPercentage) : ''} loading={stats.loading} />
        <Figure label="Average package" value={s ? lpa(s.placements.packages.averageLpa) : ''} loading={stats.loading} />
        <Figure label="Highest package" value={s ? lpa(s.placements.packages.highestLpa) : ''} loading={stats.loading} />
      </div>
      {s && s.placements.packages.counted < s.placements.placements && (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginTop: 8 }}>
          Package figures count the {s.placements.packages.counted} of {s.placements.placements} placements that state a package.
        </p>
      )}

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>By program</h2>
      <DataTable columns={programColumns} rows={s?.placements.byProgram ?? null} loading={stats.loading} error={null} onRetry={stats.refetch}
        emptyTitle="No students in a program yet" emptyDescription="Programs appear here once students are enrolled in them." rowKey={(r) => r.programId} />

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Top recruiters</h2>
      <DataTable columns={recruiterColumns} rows={s?.placements.topRecruiters ?? null} loading={stats.loading} error={null} onRetry={stats.refetch}
        emptyTitle="No placements yet" emptyDescription="Employers appear here once placements are recorded." rowKey={(r) => r.employer} />

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Applications</h2>
      <div className="card-grid">
        <Figure label="Waiting for review" value={s ? String(s.applications.submitted) : ''} loading={stats.loading} />
        <Figure label="Shortlisted" value={s ? String(s.applications.shortlisted) : ''} loading={stats.loading} />
        <Figure label="Selected" value={s ? String(s.applications.selected) : ''} loading={stats.loading} />
        <Figure label="Not taken forward" value={s ? String(s.applications.rejected) : ''} loading={stats.loading} />
        <Figure label="Withdrawn" value={s ? String(s.applications.withdrawn) : ''} loading={stats.loading} />
      </div>

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Jobs</h2>
      <div className="card-grid">
        <Figure label="Posted" value={s ? String(s.jobs.total) : ''} loading={stats.loading} />
        <Figure label="Taking applications" value={s ? String(s.jobs.open) : ''} loading={stats.loading} />
        <Figure label="Deadline passed" value={s ? String(s.jobs.deadlinePassed) : ''} loading={stats.loading} />
        <Figure label="All openings filled" value={s ? String(s.jobs.full) : ''} loading={stats.loading} />
        <Figure label="Closed by the officer" value={s ? String(s.jobs.closedByOfficer) : ''} loading={stats.loading} />
      </div>
    </div>
  );
}
