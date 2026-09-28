import { useAuth } from '../../auth/AuthContext';
import { useApiQuery } from '../../api/useApiQuery';
import type { RiskScoreResponse, RecommendationResponse, InterventionResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';

export default function StudentSuccessPage() {
  const { user } = useAuth();
  const studentId = user?.studentId ?? null;

  const risk = useApiQuery<RiskScoreResponse>(studentId ? `/risk/scores/current?studentId=${studentId}` : null);
  const recommendations = useApiQuery<RecommendationResponse[]>(risk.data ? `/risk/recommendations?riskScoreId=${risk.data.id}` : null, [risk.data]);
  const interventions = useApiQuery<InterventionResponse[]>(studentId ? `/risk/interventions?studentId=${studentId}` : null);

  const factorColumns: Column<RiskScoreResponse['factors'][number]>[] = [
    { key: 'factor', header: 'Factor', render: (f) => f.factorCode.replaceAll('_', ' ') },
    { key: 'contribution', header: 'Contribution', render: (f) => <span className="mono">{f.contribution.toFixed(1)}</span> },
  ];
  const recColumns: Column<RecommendationResponse>[] = [
    { key: 'action', header: 'Recommended action', render: (r) => r.actionCode.replaceAll('_', ' ') },
    { key: 'source', header: 'Source', render: (r) => r.generatedBy },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge value={r.status} /> },
  ];
  const intColumns: Column<InterventionResponse>[] = [
    { key: 'status', header: 'Status', render: (i) => <StatusBadge value={i.status} /> },
    { key: 'due', header: 'Due', render: (i) => i.dueDate ?? '—' },
  ];

  return (
    <div>
      <PageHeader title="Student Success" subtitle="A deterministic, evidence-based view of your academic standing — never AI-decided." />

      <div className="card-grid" style={{ marginBottom: 24 }}>
        <div className="card">
          <div className="stat-label">Risk band</div>
          {risk.loading ? <Skeleton height={32} width={80} /> : risk.error ? (
            <div style={{ fontSize: 16, color: 'var(--ink-soft)', marginTop: 6 }}>Unavailable</div>
          ) : risk.data ? (
            <div style={{ marginTop: 6 }}><StatusBadge value={risk.data.band} /></div>
          ) : (
            <div style={{ fontSize: 16, color: 'var(--ink-soft)', marginTop: 6 }}>Not yet calculated</div>
          )}
        </div>
        {risk.data && (
          <div className="card">
            <div className="stat-label">Last calculated</div>
            <div style={{ marginTop: 6, fontSize: 15 }}>{new Date(risk.data.calculatedAt).toLocaleDateString()}</div>
          </div>
        )}
      </div>

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>What's contributing to this</h2>
      {risk.loading && <Skeleton height={60} />}
      {risk.error && <div className="card"><ErrorState message="Could not load your risk information." onRetry={risk.refetch} /></div>}
      {risk.data && (
        <DataTable columns={factorColumns} rows={risk.data.factors} loading={false} error={null}
          emptyTitle="No contributing factors" emptyDescription="Your score is currently driven by no significant risk factors." rowKey={(f) => f.id} />
      )}

      {risk.data && (
        <>
          <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Recommendations</h2>
          <DataTable columns={recColumns} rows={recommendations.data} loading={recommendations.loading} error={recommendations.error}
            onRetry={recommendations.refetch} emptyTitle="No recommendations" emptyDescription="No advisor recommendations are on record right now." rowKey={(r) => r.id} />
        </>
      )}

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Interventions</h2>
      <DataTable columns={intColumns} rows={interventions.data} loading={interventions.loading} error={interventions.error}
        onRetry={interventions.refetch} emptyTitle="No interventions" emptyDescription="No support interventions are currently in progress." rowKey={(i) => i.id} />

      <p style={{ marginTop: 20, fontSize: 13, color: 'var(--ink-soft)' }}>
        This reflects the platform's deterministic risk model, not a certainty about your future performance — see the
        AI Advisor for a plain-language walkthrough grounded in your own records.
      </p>
    </div>
  );
}
