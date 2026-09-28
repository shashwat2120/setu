import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { AssessmentResponse, AssessmentComponentResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { SectionSelect, useSectionOptions } from '../../components/ui/SectionSelect';

const NEXT_ACTION: Record<string, { label: string; endpoint: string } | undefined> = {
  DRAFT: { label: 'Schedule', endpoint: 'schedule' },
  SCHEDULED: { label: 'Open', endpoint: 'open' },
  OPEN: { label: 'Close', endpoint: 'close' },
  CLOSED: { label: 'Publish', endpoint: 'publish' },
};

function AssessmentComponents({ assessmentId }: { assessmentId: string }) {
  const components = useApiQuery<AssessmentComponentResponse[]>(`/assessment/components?assessmentId=${assessmentId}`);
  const columns: Column<AssessmentComponentResponse>[] = [
    { key: 'code', header: 'Component', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'maxMarks', header: 'Max marks', render: (c) => c.maxMarks },
    { key: 'weight', header: 'Weight', render: (c) => `${c.weight}%` },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      <DataTable
        columns={columns}
        rows={components.data}
        loading={components.loading}
        error={components.error}
        onRetry={components.refetch}
        emptyTitle="No components"
        emptyDescription="This assessment has no scoring components yet."
        rowKey={(c) => c.id}
      />
    </div>
  );
}

export default function AssessmentsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const { sections, loading: sectionsLoading, courseNameFor } = useSectionOptions(tenantId);
  const [sectionId, setSectionId] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);

  const assessments = useApiQuery<AssessmentResponse[]>(
    sectionId && tenantId ? `/assessment/assessments?sectionId=${sectionId}&tenantId=${tenantId}` : null,
  );

  const transition = async (assessmentId: string, endpoint: string) => {
    await apiFetch(`/assessment/assessments/${assessmentId}/${endpoint}`, { method: 'POST' });
    assessments.refetch();
  };

  const columns: Column<AssessmentResponse>[] = [
    { key: 'title', header: 'Title', render: (a) => a.title },
    { key: 'type', header: 'Type', render: (a) => a.type },
    { key: 'totalMarks', header: 'Total marks', render: (a) => a.totalMarks },
    { key: 'window', header: 'Window', render: (a) => `${new Date(a.startsAt).toLocaleString()} → ${new Date(a.endsAt).toLocaleString()}` },
    { key: 'status', header: 'Status', render: (a) => <StatusBadge value={a.status} /> },
    {
      key: 'actions',
      header: 'Actions',
      render: (a) => {
        const next = NEXT_ACTION[a.status];
        const canVoid = a.status === 'DRAFT' || a.status === 'SCHEDULED';
        return (
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
              onClick={() => setExpanded(expanded === a.id ? null : a.id)}>
              Components
            </button>
            {next && (
              <ConfirmActionButton
                label={next.label}
                confirmMessage={`${next.label} "${a.title}"?`}
                onConfirm={() => transition(a.id, next.endpoint)}
              />
            )}
            {canVoid && (
              <ConfirmActionButton
                label="Void"
                variant="danger"
                confirmMessage={`Void "${a.title}"? This cannot be undone.`}
                onConfirm={() => transition(a.id, 'void')}
              />
            )}
          </div>
        );
      },
    },
  ];

  return (
    <div>
      <PageHeader title="Assessments" subtitle="Lifecycle: DRAFT → SCHEDULED → OPEN → CLOSED → PUBLISHED (or VOIDED)." />
      <SectionSelect sections={sections} courseNameFor={courseNameFor} value={sectionId} onChange={setSectionId} />

      {!sectionId && !sectionsLoading && (
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select a section above to view its assessments.</p></div>
      )}

      {sectionId && (
        <>
          <DataTable
            columns={columns}
            rows={assessments.data}
            loading={assessments.loading}
            error={assessments.error}
            onRetry={assessments.refetch}
            emptyTitle="No assessments"
            emptyDescription="No assessments have been created for this section yet."
            rowKey={(a) => a.id}
          />
          {expanded && <AssessmentComponents assessmentId={expanded} />}
        </>
      )}
    </div>
  );
}
