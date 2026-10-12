import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { AssessmentAttemptResponse, AssessmentResponse, AssessmentComponentResponse, QuestionResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { FacultySectionSelect, useFacultySectionOptions } from '../../components/ui/FacultySectionSelect';
import { StudentName } from '../../components/ui/StudentName';
import { useState as useFormState } from 'react';

const NEXT_ACTION: Record<string, { label: string; endpoint: string } | undefined> = {
  DRAFT: { label: 'Schedule', endpoint: 'schedule' },
  SCHEDULED: { label: 'Open', endpoint: 'open' },
  OPEN: { label: 'Close', endpoint: 'close' },
  CLOSED: { label: 'Publish', endpoint: 'publish' },
};

const QUESTION_TYPES = ['MCQ_SINGLE', 'TRUE_FALSE', 'SHORT_ANSWER', 'ESSAY'] as const;

/** The answer key each question type is graded against (what the grader reads). Students never receive it. */
const KEY_HINT: Record<string, string> = {
  MCQ_SINGLE: '{"correctOption":"B"}',
  TRUE_FALSE: '{"correctOption":"True"}',
  SHORT_ANSWER: '{"correctAnswer":"queue"}',
  ESSAY: 'No key: a person marks it',
};

function ComponentQuestions({ componentId, isDraft }: { componentId: string; isDraft: boolean }) {
  const questions = useApiQuery<QuestionResponse[]>(`/assessment/questions?componentId=${componentId}`);
  const [prompt, setPrompt] = useState('');
  const [type, setType] = useState<typeof QUESTION_TYPES[number]>('MCQ_SINGLE');
  const [points, setPoints] = useState('1');
  const [metadataJson, setMetadataJson] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  const create = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setMessage('');
    try {
      await apiFetch('/assessment/questions', {
        method: 'POST',
        body: { componentId, type, prompt, points: Number(points), difficulty: null, skillId: null, metadataJson: metadataJson || null },
      });
      setPrompt(''); setMetadataJson('');
      questions.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create question.'); }
    finally { setBusy(false); }
  };

  const remove = async (questionId: string) => {
    setBusy(true); setMessage('');
    try { await apiFetch(`/assessment/questions/${questionId}`, { method: 'DELETE' }); questions.refetch(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Could not delete question.'); }
    finally { setBusy(false); }
  };

  return (
    <div style={{ padding: '10px 14px', background: 'var(--bg-elev)' }}>
      {questions.data && questions.data.length > 0 ? (
        <table className="data-table" style={{ marginBottom: 12 }}>
          <thead><tr><th scope="col">Prompt</th><th scope="col">Type</th><th scope="col">Points</th>{isDraft && <th scope="col" /> }</tr></thead>
          <tbody>
            {questions.data.map((q) => (
              <tr key={q.id}>
                <td data-label="Prompt">{q.prompt}</td>
                <td data-label="Type" className="mono">{q.type}</td>
                <td data-label="Points">{q.points}</td>
                {isDraft && <td><button type="button" className="btn btn-ghost" style={{ padding: '2px 8px', fontSize: 11 }} disabled={busy} onClick={() => remove(q.id)}>Delete</button></td>}
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: 12 }}>No questions yet.</p>}
      {isDraft && (
        <form onSubmit={create} style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div className="form-field" style={{ marginBottom: 0, flex: 1, minWidth: 160 }}>
            <label htmlFor={`q-prompt-${componentId}`} style={{ fontSize: 11 }}>Prompt</label>
            <input id={`q-prompt-${componentId}`} value={prompt} onChange={(e) => setPrompt(e.target.value)} required style={{ fontSize: 12, padding: '5px 8px' }} />
          </div>
          <div className="form-field" style={{ marginBottom: 0 }}>
            <label htmlFor={`q-type-${componentId}`} style={{ fontSize: 11 }}>Type</label>
            <select id={`q-type-${componentId}`} value={type} onChange={(e) => setType(e.target.value as typeof QUESTION_TYPES[number])} style={{ fontSize: 12, padding: '5px 8px' }}>
              {QUESTION_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div className="form-field" style={{ marginBottom: 0, width: 70 }}>
            <label htmlFor={`q-points-${componentId}`} style={{ fontSize: 11 }}>Points</label>
            <input id={`q-points-${componentId}`} type="number" min="0" step="0.5" value={points} onChange={(e) => setPoints(e.target.value)} style={{ fontSize: 12, padding: '5px 8px' }} />
          </div>
          <div className="form-field" style={{ marginBottom: 0, flex: 1, minWidth: 160 }}>
            <label htmlFor={`q-meta-${componentId}`} style={{ fontSize: 11 }}>Answer key (JSON)</label>
            <input id={`q-meta-${componentId}`} value={metadataJson} onChange={(e) => setMetadataJson(e.target.value)}
              placeholder={KEY_HINT[type]} style={{ fontSize: 12, padding: '5px 8px' }} />
          </div>
          <button type="submit" className="btn btn-primary" style={{ padding: '6px 12px', fontSize: 12 }} disabled={busy || !prompt}>
            {busy ? 'Adding…' : 'Add question'}
          </button>
          <p style={{ flexBasis: '100%', fontSize: 11, color: 'var(--ink-soft)', margin: '4px 0 0' }}>
            For multiple choice, write the options (A, B, C…) into the prompt: students answer with the letter. The key is never shown to students.
          </p>
        </form>
      )}
      {message && <p role="status" style={{ fontSize: 11, color: 'var(--flag)', marginTop: 8 }}>{message}</p>}
    </div>
  );
}

function AssessmentComponents({ assessmentId, isDraft }: { assessmentId: string; isDraft: boolean }) {
  const components = useApiQuery<AssessmentComponentResponse[]>(`/assessment/components?assessmentId=${assessmentId}`);
  const [code, setCode] = useState('');
  const [maxMarks, setMaxMarks] = useState('');
  const [weight, setWeight] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [expandedComponent, setExpandedComponent] = useState<string | null>(null);

  const create = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setMessage('');
    try {
      await apiFetch('/assessment/components', {
        method: 'POST',
        body: { assessmentId, code, maxMarks: Number(maxMarks), weight: Number(weight), orderNo: (components.data?.length ?? 0) + 1 },
      });
      setCode(''); setMaxMarks(''); setWeight('');
      components.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create component.'); }
    finally { setBusy(false); }
  };

  const columns: Column<AssessmentComponentResponse>[] = [
    { key: 'code', header: 'Component', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'maxMarks', header: 'Max marks', render: (c) => c.maxMarks },
    { key: 'weight', header: 'Weight', render: (c) => `${c.weight}%` },
    {
      key: 'questions', header: 'Questions', render: (c) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }}
          onClick={() => setExpandedComponent(expandedComponent === c.id ? null : c.id)}>
          {expandedComponent === c.id ? 'Hide questions' : 'Questions'}
        </button>
      ),
    },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      <DataTable columns={columns} rows={components.data} loading={components.loading} error={components.error}
        onRetry={components.refetch} emptyTitle="No components" rowKey={(c) => c.id} />
      {expandedComponent && <ComponentQuestions componentId={expandedComponent} isDraft={isDraft} />}
      {isDraft && (
        <form onSubmit={create} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 10 }}>
          <div className="form-field" style={{ marginBottom: 0 }}>
            <label htmlFor={`c-code-${assessmentId}`} style={{ fontSize: 12 }}>Code</label>
            <input id={`c-code-${assessmentId}`} value={code} onChange={(e) => setCode(e.target.value)} required style={{ fontSize: 13, padding: '6px 8px' }} />
          </div>
          <div className="form-field" style={{ marginBottom: 0, width: 100 }}>
            <label htmlFor={`c-marks-${assessmentId}`} style={{ fontSize: 12 }}>Max marks</label>
            <input id={`c-marks-${assessmentId}`} type="number" min="0" step="0.5" value={maxMarks} onChange={(e) => setMaxMarks(e.target.value)} required style={{ fontSize: 13, padding: '6px 8px' }} />
          </div>
          <div className="form-field" style={{ marginBottom: 0, width: 100 }}>
            <label htmlFor={`c-weight-${assessmentId}`} style={{ fontSize: 12 }}>Weight %</label>
            <input id={`c-weight-${assessmentId}`} type="number" min="0" max="100" step="0.5" value={weight} onChange={(e) => setWeight(e.target.value)} required style={{ fontSize: 13, padding: '6px 8px' }} />
          </div>
          <button type="submit" className="btn btn-primary" style={{ padding: '7px 14px', fontSize: 13 }} disabled={busy || !code}>
            {busy ? 'Adding…' : 'Add component'}
          </button>
        </form>
      )}
      {!isDraft && <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginTop: 8 }}>Components and questions can only be added while this assessment is DRAFT.</p>}
      {message && <p role="status" style={{ fontSize: 12, color: 'var(--flag)', marginTop: 8 }}>{message}</p>}
    </div>
  );
}

/** Attempts at one assessment, with the Evaluate action that auto-grades the questions that have an answer key. */
function AssessmentAttempts({ assessmentId }: { assessmentId: string }) {
  const attempts = useApiQuery<AssessmentAttemptResponse[]>(`/assessment/attempts?assessmentId=${assessmentId}`);
  const [message, setMessage] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);

  const evaluate = async (attemptId: string) => {
    setBusyId(attemptId); setMessage('');
    try {
      const r = await apiFetch<{ totalScore: number; autoGradedCount: number; manualPendingCount: number }>(`/assessment/attempts/${attemptId}/evaluate`, { method: 'POST' });
      setMessage(`Score ${r.totalScore}: ${r.autoGradedCount} question(s) graded automatically, ${r.manualPendingCount} waiting for a person to mark.`);
      attempts.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not evaluate this attempt.'); }
    finally { setBusyId(null); }
  };

  const columns: Column<AssessmentAttemptResponse>[] = [
    { key: 'student', header: 'Student', render: (t) => <StudentName studentId={t.studentId} /> },
    { key: 'attempt', header: 'Attempt', render: (t) => t.attemptNo },
    { key: 'status', header: 'Status', render: (t) => <StatusBadge value={t.status} /> },
    { key: 'score', header: 'Score', render: (t) => (t.score === null ? '—' : t.score) },
    { key: 'actions', header: '', render: (t) => (t.status === 'SUBMITTED' ? (
      <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} disabled={busyId === t.id} onClick={() => evaluate(t.id)}>
        {busyId === t.id ? 'Evaluating…' : 'Evaluate'}
      </button>
    ) : null) },
  ];

  return (
    <div style={{ padding: '4px 0 16px' }}>
      <h3 style={{ fontSize: 15, margin: '16px 0 8px' }}>Attempts</h3>
      {message && <p role="status" style={{ fontSize: 13, marginBottom: 8 }}>{message}</p>}
      <DataTable columns={columns} rows={attempts.data} loading={attempts.loading} error={attempts.error} onRetry={attempts.refetch}
        emptyTitle="No attempts yet" emptyDescription="No student has started this assessment." rowKey={(t) => t.id} />
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
  const [attemptsFor, setAttemptsFor] = useState<string | null>(null);
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
            {(a.status === 'OPEN' || a.status === 'CLOSED' || a.status === 'PUBLISHED') && (
              <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
                onClick={() => setAttemptsFor(attemptsFor === a.id ? null : a.id)}>
                Attempts
              </button>
            )}
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
          {expanded && (
            <AssessmentComponents assessmentId={expanded}
              isDraft={assessments.data?.find((a) => a.id === expanded)?.status === 'DRAFT'} />
          )}
          {attemptsFor && <AssessmentAttempts assessmentId={attemptsFor} />}
        </>
      )}
    </div>
  );
}
