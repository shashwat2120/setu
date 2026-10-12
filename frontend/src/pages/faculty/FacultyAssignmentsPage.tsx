import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { LearningAssignmentResponse, AssignmentSubmissionResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StudentName } from '../../components/ui/StudentName';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { FacultySectionSelect, useFacultySectionOptions } from '../../components/ui/FacultySectionSelect';

const inputStyle = {
  padding: '8px 10px', fontSize: 13, border: '1px solid var(--rule)', borderRadius: 4,
  background: 'var(--bg-elev)', color: 'var(--ink)',
};

function GradeCell({ submission, onGraded }: { submission: AssignmentSubmissionResponse; onGraded: () => void }) {
  const [marks, setMarks] = useState(submission.marks != null ? String(submission.marks) : '');
  const [feedback, setFeedback] = useState(submission.feedback ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    setBusy(true); setError('');
    try {
      await apiFetch(`/learning/submissions/${submission.id}/grade`, {
        method: 'POST', body: { marks: marks === '' ? null : Number(marks), feedback: feedback || null },
      });
      onGraded();
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save grade.'); }
    finally { setBusy(false); }
  };

  return (
    <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
      <input aria-label="Marks" placeholder="Marks" value={marks} onChange={(e) => setMarks(e.target.value)}
        style={{ ...inputStyle, width: 70, padding: '4px 6px', fontSize: 12 }} />
      <input aria-label="Feedback" placeholder="Feedback" value={feedback} onChange={(e) => setFeedback(e.target.value)}
        style={{ ...inputStyle, width: 160, padding: '4px 6px', fontSize: 12 }} />
      <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} disabled={busy} onClick={submit}>
        {busy ? 'Saving…' : submission.status === 'GRADED' ? 'Update' : 'Grade'}
      </button>
      {error && <span style={{ fontSize: 11, color: 'var(--flag)' }}>{error}</span>}
    </div>
  );
}

function Submissions({ assignmentId }: { assignmentId: string }) {
  const submissions = useApiQuery<AssignmentSubmissionResponse[]>(`/learning/submissions?assignmentId=${assignmentId}`);
  const columns: Column<AssignmentSubmissionResponse>[] = [
    { key: 'student', header: 'Student', render: (s) => <StudentName studentId={s.studentId} /> },
    { key: 'attempt', header: 'Attempt', render: (s) => s.attemptNo },
    { key: 'status', header: 'Status', render: (s) => <StatusBadge value={s.status} /> },
    { key: 'submittedAt', header: 'Submitted', render: (s) => (s.submittedAt ? new Date(s.submittedAt).toLocaleString() : '—') },
    { key: 'grade', header: 'Marks / feedback', render: (s) => <GradeCell submission={s} onGraded={submissions.refetch} /> },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      <DataTable columns={columns} rows={submissions.data} loading={submissions.loading} error={submissions.error}
        onRetry={submissions.refetch} emptyTitle="No submissions" emptyDescription="No students have submitted yet." rowKey={(s) => s.id} />
    </div>
  );
}

export default function FacultyAssignmentsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;
  const { sections, loading: sectionsLoading } = useFacultySectionOptions(tenantId, facultyId);
  const [sectionId, setSectionId] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [maxMarks, setMaxMarks] = useState('');
  const [dueAt, setDueAt] = useState('');
  const [creating, setCreating] = useState(false);

  const assignments = useApiQuery<LearningAssignmentResponse[]>(
    sectionId && tenantId ? `/learning/assignments?sectionId=${sectionId}&tenantId=${tenantId}` : null,
  );

  const create = async () => {
    setCreating(true);
    try {
      await apiFetch('/learning/assignments', {
        method: 'POST',
        body: { sectionId, title, maxMarks: Number(maxMarks), dueAt: new Date(dueAt).toISOString(), latePolicy: null },
      });
      setTitle(''); setMaxMarks(''); setDueAt('');
      assignments.refetch();
    } finally {
      setCreating(false);
    }
  };

  const transition = async (assignmentId: string, action: 'publish' | 'close') => {
    await apiFetch(`/learning/assignments/${assignmentId}/${action}`, { method: 'POST' });
    assignments.refetch();
  };

  const columns: Column<LearningAssignmentResponse>[] = [
    { key: 'title', header: 'Title', render: (a) => a.title },
    { key: 'maxMarks', header: 'Max marks', render: (a) => a.maxMarks },
    { key: 'dueAt', header: 'Due', render: (a) => new Date(a.dueAt).toLocaleString() },
    { key: 'status', header: 'Status', render: (a) => <StatusBadge value={a.status} /> },
    {
      key: 'actions', header: 'Actions', render: (a) => (
        <div style={{ display: 'flex', gap: 8 }}>
          <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
            onClick={() => setExpanded(expanded === a.id ? null : a.id)}>
            Submissions
          </button>
          {a.status === 'DRAFT' && (
            <ConfirmActionButton label="Publish" confirmMessage={`Publish "${a.title}"?`} onConfirm={() => transition(a.id, 'publish')} />
          )}
          {a.status === 'PUBLISHED' && (
            <ConfirmActionButton label="Close" variant="danger" confirmMessage={`Close "${a.title}"? No further submissions will be accepted.`} onConfirm={() => transition(a.id, 'close')} />
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Assignments" subtitle="Create, publish, and review submissions for the sections you teach." />
      <FacultySectionSelect sections={sections} value={sectionId} onChange={setSectionId} />

      {!sectionId && !sectionsLoading && (
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select a section above to manage its assignments.</p></div>
      )}

      {sectionId && (
        <>
          <DataTable columns={columns} rows={assignments.data} loading={assignments.loading} error={assignments.error}
            onRetry={assignments.refetch} emptyTitle="No assignments" emptyDescription="No assignments have been created for this section yet." rowKey={(a) => a.id} />

          <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
            <input aria-label="Assignment title" placeholder="New assignment title" value={title} onChange={(e) => setTitle(e.target.value)}
              style={{ ...inputStyle, flex: 1, minWidth: 180 }} />
            <input aria-label="Max marks" placeholder="Max marks" value={maxMarks} onChange={(e) => setMaxMarks(e.target.value)} style={{ ...inputStyle, width: 100 }} />
            <input aria-label="Due date" type="datetime-local" value={dueAt} onChange={(e) => setDueAt(e.target.value)} style={inputStyle} />
            <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }}
              disabled={creating || !title || !maxMarks || !dueAt} onClick={create}>
              {creating ? 'Creating…' : 'Create assignment'}
            </button>
          </div>

          {expanded && <Submissions assignmentId={expanded} />}
        </>
      )}
    </div>
  );
}
