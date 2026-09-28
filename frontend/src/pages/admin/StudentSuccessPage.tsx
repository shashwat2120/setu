import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { RiskScoreResponse, RecommendationResponse, InterventionResponse, StudentResponse, PageResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';

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
    { key: 'generatedBy', header: 'Source', render: (r) => r.generatedBy },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge value={r.status} /> },
    {
      key: 'actions',
      header: 'Actions',
      render: (r) => r.status === 'PROPOSED' ? (
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
    { key: 'actions', header: 'Actions', render: (i) => {
      if (i.status === 'PLANNED') {
        return <ConfirmActionButton label="Start" confirmMessage="Move this intervention to in progress?" onConfirm={() => transitionIntervention(i.id, 'IN_PROGRESS')} />;
      }
      if (i.status === 'IN_PROGRESS') {
        return (
          <div style={{ display: 'flex', gap: 8 }}>
            <ConfirmActionButton label="Complete" confirmMessage="Mark this intervention complete? At least one action must already be recorded." onConfirm={() => transitionIntervention(i.id, 'COMPLETED')} />
            <ConfirmActionButton label="Cancel" variant="danger" confirmMessage="Cancel this intervention?" onConfirm={() => transitionIntervention(i.id, 'CANCELLED')} />
          </div>
        );
      }
      return null;
    } },
  ];

  return (
    <div style={{ padding: '8px 0 20px' }}>
      <h3 style={{ fontSize: 15, marginBottom: 8 }}>Risk factors — {studentName}</h3>
      <DataTable columns={factorColumns} rows={riskScore.factors} loading={false} error={null}
        emptyTitle="No factors recorded" rowKey={(f) => f.id} />

      <h3 style={{ fontSize: 15, margin: '20px 0 8px' }}>Recommendations</h3>
      <DataTable columns={recColumns} rows={recommendations.data} loading={recommendations.loading} error={recommendations.error}
        onRetry={recommendations.refetch} emptyTitle="No recommendations" rowKey={(r) => r.id} />

      <h3 style={{ fontSize: 15, margin: '20px 0 8px' }}>Interventions</h3>
      <DataTable columns={intColumns} rows={interventions.data} loading={interventions.loading} error={interventions.error}
        onRetry={interventions.refetch} emptyTitle="No interventions" rowKey={(i) => i.id} />
    </div>
  );
}

export default function StudentSuccessPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [expanded, setExpanded] = useState<string | null>(null);

  const scores = useApiQuery<RiskScoreResponse[]>(tenantId ? `/risk/scores?tenantId=${tenantId}` : null);
  const students = useApiQuery<PageResponse<StudentResponse>>(
    tenantId ? `/directory/students?tenantId=${tenantId}&size=500` : null,
  );
  const studentNameFor = (studentId: string) =>
    students.data?.content.find((s) => s.id === studentId)?.fullName
    ?? students.data?.content.find((s) => s.id === studentId)?.email
    ?? studentId;

  const sorted = scores.data ? [...scores.data].sort((a, b) => b.score - a.score) : null;

  const columns: Column<RiskScoreResponse>[] = [
    { key: 'student', header: 'Student', render: (r) => studentNameFor(r.studentId) },
    { key: 'band', header: 'Risk band', render: (r) => <StatusBadge value={r.band} /> },
    { key: 'score', header: 'Score', render: (r) => <span className="mono">{r.score}</span> },
    { key: 'calculatedAt', header: 'Last calculated', render: (r) => new Date(r.calculatedAt).toLocaleDateString() },
    {
      key: 'actions',
      header: '',
      render: (r) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpanded(expanded === r.id ? null : r.id)}>
          {expanded === r.id ? 'Hide' : 'Details'}
        </button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Student Success" subtitle="Deterministic risk scores, recommendations, and interventions — never AI-decided." />
      <DataTable
        columns={columns}
        rows={sorted}
        loading={scores.loading || students.loading}
        error={scores.error}
        onRetry={scores.refetch}
        emptyTitle="No risk scores"
        emptyDescription="No student risk scores have been calculated for this college yet."
        rowKey={(r) => r.id}
      />
      {expanded && sorted && (
        <RiskDetail
          riskScore={sorted.find((r) => r.id === expanded)!}
          studentName={studentNameFor(sorted.find((r) => r.id === expanded)!.studentId)}
        />
      )}
    </div>
  );
}
