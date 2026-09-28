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
import { FacultySectionSelect, useFacultySectionOptions } from '../../components/ui/FacultySectionSelect';
import { useState as useFormState } from 'react';

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
      <DataTable columns={columns} rows={components.data} loading={components.loading} error={components.error}
        onRetry={components.refetch} emptyTitle="No components" rowKey={(c) => c.id} />
    </div>
  );
}

export default function FacultyAssessmentsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;
  const { sections, loading: sectionsLoading } = useFacultySectionOptions(tenantId, facultyId);
  const [sectionId, setSectionId] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [assessmentTitle, setAssessmentTitle] = useFormState('');
  const [assessmentType, setAssessmentType] = useFormState('QUIZ');
  const [marks, setMarks] = useFormState('100');
  const [startsAt, setStartsAt] = useFormState('');
  const [endsAt, setEndsAt] = useFormState('');
  const [createMessage, setCreateMessage] = useFormState('');
  const [creating, setCreating] = useFormState(false);

  const assessments = useApiQuery<AssessmentResponse[]>(
    sectionId && tenantId ? `/assessment/assessments?sectionId=${sectionId}&tenantId=${tenantId}` : null,
  );

  const transition = async (assessmentId: string, endpoint: string) => {
    await apiFetch(`/assessment/assessments/${assessmentId}/${endpoint}`, { method: 'POST' });
    assessments.refetch();
  };

  async function createAssessment(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!sectionId) return;
    setCreating(true); setCreateMessage('');
    try {
      await apiFetch('/assessment/assessments', { method: 'POST', body: {
        sectionId, type: assessmentType, title: assessmentTitle, totalMarks: Number(marks), durationMinutes: null,
        startsAt: new Date(startsAt).toISOString(), endsAt: new Date(endsAt).toISOString(),
      } });
      setAssessmentTitle(''); setCreateMessage('Assessment created as a draft.'); assessments.refetch();
    } catch (e) { setCreateMessage(e instanceof Error ? e.message : 'Could not create assessment.'); }
    finally { setCreating(false); }
  }

  const columns: Column<AssessmentResponse>[] = [
    { key: 'title', header: 'Title', render: (a) => a.title },
    { key: 'type', header: 'Type', render: (a) => a.type },
    { key: 'totalMarks', header: 'Total marks', render: (a) => a.totalMarks },
    { key: 'window', header: 'Window', render: (a) => `${new Date(a.startsAt).toLocaleString()} → ${new Date(a.endsAt).toLocaleString()}` },
    { key: 'status', header: 'Status', render: (a) => <StatusBadge value={a.status} /> },
    {
      key: 'actions', header: 'Actions', render: (a) => {
        const next = NEXT_ACTION[a.status];
        const canVoid = a.status === 'DRAFT' || a.status === 'SCHEDULED';
        return (
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
              onClick={() => setExpanded(expanded === a.id ? null : a.id)}>
              Components
            </button>
            {next && <ConfirmActionButton label={next.label} confirmMessage={`${next.label} "${a.title}"?`} onConfirm={() => transition(a.id, next.endpoint)} />}
            {canVoid && <ConfirmActionButton label="Void" variant="danger" confirmMessage={`Void "${a.title}"? This cannot be undone.`} onConfirm={() => transition(a.id, 'void')} />}
          </div>
        );
      },
    },
  ];

  return (
    <div>
      <PageHeader title="Assessments" subtitle="Lifecycle: DRAFT → SCHEDULED → OPEN → CLOSED → PUBLISHED (or VOIDED)." />
      <FacultySectionSelect sections={sections} value={sectionId} onChange={setSectionId} />

      {!sectionId && !sectionsLoading && (
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select a section above to view its assessments.</p></div>
      )}

      {sectionId && (
        <>
          <form className="card" onSubmit={createAssessment} style={{ margin: '16px 0' }}>
            <h2 style={{ fontSize: 17, marginBottom: 12 }}>Create assessment</h2>
            <div className="form-field"><label htmlFor="assessment-title">Title</label><input id="assessment-title" value={assessmentTitle} onChange={(e) => setAssessmentTitle(e.target.value)} required /></div>
            <div className="form-field"><label htmlFor="assessment-type">Type</label><select id="assessment-type" value={assessmentType} onChange={(e) => setAssessmentType(e.target.value)}><option>QUIZ</option><option>EXAM</option><option>ASSIGNMENT</option><option>MIDTERM</option><option>FINAL</option></select></div>
            <div className="form-field"><label htmlFor="assessment-marks">Total marks</label><input id="assessment-marks" type="number" min="1" step="0.5" value={marks} onChange={(e) => setMarks(e.target.value)} required /></div>
            <div className="form-field"><label htmlFor="assessment-start">Starts</label><input id="assessment-start" type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required /></div>
            <div className="form-field"><label htmlFor="assessment-end">Ends</label><input id="assessment-end" type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} required /></div>
            {createMessage && <p role="status" style={{ marginBottom: 10 }}>{createMessage}</p>}
            <button className="btn btn-primary" disabled={creating}>{creating ? 'Creating…' : 'Create draft'}</button>
          </form>
          <DataTable columns={columns} rows={assessments.data} loading={assessments.loading} error={assessments.error}
            onRetry={assessments.refetch} emptyTitle="No assessments" emptyDescription="No assessments have been created for this section yet." rowKey={(a) => a.id} />
          {expanded && <AssessmentComponents assessmentId={expanded} />}
        </>
      )}
    </div>
  );
}
