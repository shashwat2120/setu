import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  FacultyAssignmentResponse, CourseSectionResponse, EnrollmentResponse, StudentResponse, PageResponse,
  RiskScoreResponse, RecommendationResponse, InterventionResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';

interface AdviseeRow {
  student: StudentResponse;
  riskScore: RiskScoreResponse | null;
}

function RiskDetail({ riskScore, studentName }: { riskScore: RiskScoreResponse; studentName: string }) {
  const recommendations = useApiQuery<RecommendationResponse[]>(`/risk/recommendations?riskScoreId=${riskScore.id}`);
  const interventions = useApiQuery<InterventionResponse[]>(`/risk/interventions?studentId=${riskScore.studentId}`);

  const decide = async (recommendationId: string, action: 'accept' | 'dismiss') => {
    await apiFetch(`/risk/recommendations/${recommendationId}/${action}`, {
      method: 'POST',
      body: action === 'accept' ? { dueDate: new Date(Date.now() + 14 * 86400_000).toISOString().slice(0, 10) } : undefined,
    });
    recommendations.refetch();
    interventions.refetch();
  };

  const transitionIntervention = async (interventionId: string, target: string) => {
    await apiFetch(`/risk/interventions/${interventionId}/transition`, { method: 'POST', body: { target } });
    interventions.refetch();
  };

  const factorColumns: Column<RiskScoreResponse['factors'][number]>[] = [
    { key: 'code', header: 'Factor', render: (f) => f.factorCode },
    { key: 'contribution', header: 'Contribution', render: (f) => f.contribution },
  ];
  const recColumns: Column<RecommendationResponse>[] = [
    { key: 'action', header: 'Recommended action', render: (r) => r.actionCode },
    { key: 'source', header: 'Source', render: (r) => r.generatedBy },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge value={r.status} /> },
    {
      key: 'actions', header: 'Actions', render: (r) => r.status === 'PROPOSED' ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <ConfirmActionButton label="Accept" confirmMessage="Accept this recommendation and open an intervention?" onConfirm={() => decide(r.id, 'accept')} />
          <ConfirmActionButton label="Dismiss" variant="danger" confirmMessage="Dismiss this recommendation?" onConfirm={() => decide(r.id, 'dismiss')} />
        </div>
      ) : null,
    },
  ];
  const intColumns: Column<InterventionResponse>[] = [
    { key: 'status', header: 'Status', render: (i) => <StatusBadge value={i.status} /> },
    { key: 'dueDate', header: 'Due', render: (i) => i.dueDate ?? '—' },
    {
      key: 'actions', header: 'Actions', render: (i) => {
        if (i.status === 'PLANNED') return <ConfirmActionButton label="Start" confirmMessage="Move this intervention to in progress?" onConfirm={() => transitionIntervention(i.id, 'IN_PROGRESS')} />;
        if (i.status === 'IN_PROGRESS') {
          return (
            <div style={{ display: 'flex', gap: 8 }}>
              <ConfirmActionButton label="Complete" confirmMessage="Mark this intervention complete? At least one action must already be recorded." onConfirm={() => transitionIntervention(i.id, 'COMPLETED')} />
              <ConfirmActionButton label="Cancel" variant="danger" confirmMessage="Cancel this intervention?" onConfirm={() => transitionIntervention(i.id, 'CANCELLED')} />
            </div>
          );
        }
        return null;
      },
    },
  ];

  return (
    <div style={{ padding: '8px 0 20px' }}>
      <h3 style={{ fontSize: 15, marginBottom: 8 }}>Risk factors — {studentName}</h3>
      <DataTable columns={factorColumns} rows={riskScore.factors} loading={false} error={null} emptyTitle="No factors recorded" rowKey={(f) => f.id} />
      <h3 style={{ fontSize: 15, margin: '20px 0 8px' }}>Recommendations</h3>
      <DataTable columns={recColumns} rows={recommendations.data} loading={recommendations.loading} error={recommendations.error}
        onRetry={recommendations.refetch} emptyTitle="No recommendations" rowKey={(r) => r.id} />
      <h3 style={{ fontSize: 15, margin: '20px 0 8px' }}>Interventions</h3>
      <DataTable columns={intColumns} rows={interventions.data} loading={interventions.loading} error={interventions.error}
        onRetry={interventions.refetch} emptyTitle="No interventions" rowKey={(i) => i.id} />
    </div>
  );
}

export default function FacultyStudentSuccessPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;
  const [expanded, setExpanded] = useState<string | null>(null);

  const { data: assignments } = useApiQuery<FacultyAssignmentResponse[]>(
    facultyId && tenantId ? `/enrollment/faculty-assignments?facultyId=${facultyId}&tenantId=${tenantId}` : null,
  );

  const [rows, setRows] = useState<AdviseeRow[] | null>(null);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    if (!assignments || !tenantId) return;
    const active = assignments.filter((a) => a.active);
    if (active.length === 0) { setRows([]); return; }
    let cancelled = false;
    setError(null);
    (async () => {
      try {
        const perSection = await Promise.all(active.map(async (a) => {
          await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${a.sectionId}`).catch(() => null);
          const enrollments = await apiFetch<PageResponse<EnrollmentResponse>>(
            `/enrollment/enrollments/section/${a.sectionId}?tenantId=${tenantId}&size=100`,
          ).catch(() => null);
          const activeEnrollments = enrollments?.content.filter((e) => e.status === 'ENROLLED') ?? [];
          return Promise.all(activeEnrollments.map(async (e) => {
            const student = await apiFetch<StudentResponse>(`/directory/students/${e.studentId}`).catch(() => null);
            if (!student) return null;
            const riskScore = await apiFetch<RiskScoreResponse>(`/risk/scores/current?studentId=${e.studentId}`).catch(() => null);
            return { student, riskScore } as AdviseeRow;
          }));
        }));
        if (!cancelled) {
          const flat = perSection.flat().filter((r): r is AdviseeRow => r !== null);
          const byStudent = new Map(flat.map((r) => [r.student.id, r]));
          setRows(Array.from(byStudent.values()));
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e : new Error(String(e)));
      }
    })();
    return () => { cancelled = true; };
  }, [assignments, tenantId]);

  const sorted = rows ? [...rows].sort((a, b) => (b.riskScore?.score ?? -1) - (a.riskScore?.score ?? -1)) : null;

  const columns: Column<AdviseeRow>[] = [
    { key: 'name', header: 'Student', render: (r) => r.student.fullName ?? r.student.email ?? '—' },
    { key: 'band', header: 'Risk band', render: (r) => (r.riskScore ? <StatusBadge value={r.riskScore.band} /> : <span style={{ color: 'var(--ink-soft)', fontSize: 13 }}>not calculated</span>) },
    { key: 'score', header: 'Score', render: (r) => (r.riskScore ? <span className="mono">{r.riskScore.score}</span> : '—') },
    {
      key: 'actions', header: '', render: (r) => (r.riskScore ? (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpanded(expanded === r.riskScore!.id ? null : r.riskScore!.id)}>
          {expanded === r.riskScore.id ? 'Hide' : 'Details'}
        </button>
      ) : null),
    },
  ];

  return (
    <div>
      <PageHeader title="Student Success" subtitle="Deterministic risk scores for students in your assigned sections — never AI-decided." />
      {!sorted && !error ? (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}><Skeleton height={20} /><Skeleton height={20} /></div>
      ) : error ? (
        <div className="card"><ErrorState message="Could not load your advisees." /></div>
      ) : (
        <DataTable columns={columns} rows={sorted} loading={false} error={null}
          emptyTitle="No advisees" emptyDescription="No students are currently enrolled in your assigned sections." rowKey={(r) => r.student.id} />
      )}
      {expanded && sorted && (() => {
        const row = sorted.find((r) => r.riskScore?.id === expanded);
        return row?.riskScore ? <RiskDetail riskScore={row.riskScore} studentName={row.student.fullName ?? row.student.email ?? ''} /> : null;
      })()}
    </div>
  );
}
