import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  EnrollmentResponse, CourseSectionResponse, CourseResponse, CourseModuleResponse, LessonResponse,
  LearningProgressResponse, LearningAssignmentResponse, AssignmentSubmissionResponse, AssessmentResponse,
  AssessmentComponentResponse, AssessmentAttemptResponse, QuestionResponse, AssessmentResponseRecord,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';

const inputStyle = { padding: '8px 10px', fontSize: 13, border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)' };

function LessonRow({ lesson, studentId }: { lesson: LessonResponse; studentId: string }) {
  const progress = useApiQuery<LearningProgressResponse>(`/learning/progress?lessonId=${lesson.id}&studentId=${studentId}`);
  const [saving, setSaving] = useState(false);

  const markComplete = async () => {
    setSaving(true);
    try {
      await apiFetch('/learning/progress', { method: 'POST', body: { lessonId: lesson.id, state: 'COMPLETED', percent: 100, lastPosition: null } });
      progress.refetch();
    } finally {
      setSaving(false);
    }
  };
  const markInProgress = async () => {
    setSaving(true);
    try {
      await apiFetch('/learning/progress', { method: 'POST', body: { lessonId: lesson.id, state: 'IN_PROGRESS', percent: 50, lastPosition: null } });
      progress.refetch();
    } finally {
      setSaving(false);
    }
  };

  const state = progress.data?.state ?? 'NOT_STARTED';

  return (
    <tr>
      <td data-label="Lesson">{lesson.title}</td>
      <td data-label="Type">{lesson.type ?? '—'}</td>
      <td data-label="Progress">{progress.loading ? <Skeleton height={16} width={70} /> : <StatusBadge value={state} />}</td>
      <td data-label="Actions">
        <div style={{ display: 'flex', gap: 8 }}>
          {state === 'NOT_STARTED' && (
            <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} disabled={saving} onClick={markInProgress}>
              Start
            </button>
          )}
          {state !== 'COMPLETED' && (
            <button type="button" className="btn btn-primary" style={{ padding: '6px 10px', fontSize: 12 }} disabled={saving} onClick={markComplete}>
              Mark complete
            </button>
          )}
        </div>
      </td>
    </tr>
  );
}

function ModuleSection({ module, studentId }: { module: CourseModuleResponse; studentId: string }) {
  const lessons = useApiQuery<LessonResponse[]>(`/learning/lessons?moduleId=${module.id}`);
  return (
    <div style={{ marginBottom: 16 }}>
      <h3 style={{ fontSize: 15, marginBottom: 8 }}>{module.title}</h3>
      {lessons.loading && <Skeleton height={60} />}
      {lessons.error && <ErrorState message="Could not load lessons." onRetry={lessons.refetch} />}
      {lessons.data && lessons.data.length === 0 && <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>No lessons in this module yet.</p>}
      {lessons.data && lessons.data.length > 0 && (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <table className="data-table">
            <thead>
              <tr><th scope="col">Lesson</th><th scope="col">Type</th><th scope="col">Progress</th><th scope="col">Actions</th></tr>
            </thead>
            <tbody>
              {lessons.data.map((l) => <LessonRow key={l.id} lesson={l} studentId={studentId} />)}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function AssignmentRow({ assignment }: { assignment: LearningAssignmentResponse }) {
  const submissions = useApiQuery<AssignmentSubmissionResponse[]>(`/learning/submissions?assignmentId=${assignment.id}`);
  const [contentRef, setContentRef] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const mySubmissions = submissions.data ?? [];
  const latest = mySubmissions[mySubmissions.length - 1];

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await apiFetch('/learning/submissions', { method: 'POST', body: { assignmentId: assignment.id, contentRef } });
      setContentRef('');
      submissions.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not submit.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="card" style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 8 }}>
        <div>
          <strong>{assignment.title}</strong>
          <div style={{ fontSize: 13, color: 'var(--ink-soft)', marginTop: 4 }}>
            Max marks {assignment.maxMarks} · Due {new Date(assignment.dueAt).toLocaleString()}
          </div>
        </div>
        <StatusBadge value={assignment.status} />
      </div>
      {submissions.loading ? <Skeleton height={20} /> : latest ? (
        <p style={{ fontSize: 13, marginTop: 10 }}>
          Your submission: <StatusBadge value={latest.status} /> (attempt {latest.attemptNo}, {latest.submittedAt ? new Date(latest.submittedAt).toLocaleString() : 'pending'})
        </p>
      ) : (
        assignment.status === 'PUBLISHED' && (
          <div style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
            <input aria-label="Submission reference" placeholder="ref://your-submission" value={contentRef}
              onChange={(e) => setContentRef(e.target.value)} style={{ ...inputStyle, flex: 1, minWidth: 200 }} />
            <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }}
              disabled={submitting || !contentRef} onClick={submit}>
              {submitting ? 'Submitting…' : 'Submit'}
            </button>
          </div>
        )
      )}
      {error && <p style={{ fontSize: 12, color: 'var(--flag)', marginTop: 6 }}>{error}</p>}
    </div>
  );
}

function QuestionAnswer({ question, attemptId }: { question: QuestionResponse; attemptId: string }) {
  const existing = useApiQuery<AssessmentResponseRecord[]>(`/assessment/responses?attemptId=${attemptId}`);
  const mine = existing.data?.find((r) => r.questionId === question.id);
  const [answer, setAnswer] = useState('');
  const [saving, setSaving] = useState(false);

  const save = async () => {
    setSaving(true);
    try {
      await apiFetch('/assessment/responses', { method: 'POST', body: { attemptId, questionId: question.id, answerJson: JSON.stringify(answer) } });
      setAnswer('');
      existing.refetch();
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="card" style={{ marginBottom: 10 }}>
      <p style={{ fontSize: 14, marginBottom: 8 }}>{question.prompt} <span style={{ color: 'var(--ink-soft)', fontSize: 12 }}>({question.points} pts)</span></p>
      {mine ? (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>
          Answered {mine.isCorrect === true ? '✓ correct' : mine.isCorrect === false ? '✗ incorrect' : '(pending evaluation)'}
        </p>
      ) : (
        <div style={{ display: 'flex', gap: 8 }}>
          <input aria-label="Your answer" placeholder="Your answer" value={answer} onChange={(e) => setAnswer(e.target.value)}
            style={{ ...inputStyle, flex: 1 }} />
          <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }} disabled={saving || !answer} onClick={save}>
            {saving ? 'Saving…' : 'Save answer'}
          </button>
        </div>
      )}
    </div>
  );
}

function AttemptQuestions({ assessmentId, attemptId }: { assessmentId: string; attemptId: string }) {
  const components = useApiQuery<AssessmentComponentResponse[]>(`/assessment/components?assessmentId=${assessmentId}`);
  return (
    <div style={{ marginTop: 12 }}>
      {components.loading && <Skeleton height={40} />}
      {components.data?.map((c) => <ComponentQuestions key={c.id} component={c} attemptId={attemptId} />)}
    </div>
  );
}

function ComponentQuestions({ component, attemptId }: { component: AssessmentComponentResponse; attemptId: string }) {
  const questions = useApiQuery<QuestionResponse[]>(`/assessment/questions?componentId=${component.id}`);
  return (
    <div style={{ marginBottom: 16 }}>
      <h4 style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 8 }}>{component.code}</h4>
      {questions.data?.map((q) => <QuestionAnswer key={q.id} question={q} attemptId={attemptId} />)}
    </div>
  );
}

function AssessmentCard({ assessment }: { assessment: AssessmentResponse }) {
  const attempts = useApiQuery<AssessmentAttemptResponse[]>(`/assessment/attempts?assessmentId=${assessment.id}`);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const myAttempt = attempts.data?.[0];

  const start = async () => {
    setStarting(true);
    setError(null);
    try {
      await apiFetch('/assessment/attempts', { method: 'POST', body: { assessmentId: assessment.id } });
      attempts.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start this attempt.');
    } finally {
      setStarting(false);
    }
  };

  const submitAttempt = async () => {
    if (!myAttempt) return;
    await apiFetch(`/assessment/attempts/${myAttempt.id}/submit`, { method: 'POST' });
    attempts.refetch();
  };

  return (
    <div className="card" style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 8 }}>
        <div>
          <strong>{assessment.title}</strong>
          <div style={{ fontSize: 13, color: 'var(--ink-soft)', marginTop: 4 }}>
            {assessment.type} · {assessment.totalMarks} marks · window {new Date(assessment.startsAt).toLocaleString()} → {new Date(assessment.endsAt).toLocaleString()}
          </div>
        </div>
        <StatusBadge value={assessment.status} />
      </div>

      {attempts.loading ? <Skeleton height={20} /> : myAttempt ? (
        <div style={{ marginTop: 10 }}>
          <p style={{ fontSize: 13 }}>
            Your attempt: <StatusBadge value={myAttempt.status} /> {myAttempt.score !== null && `— score ${myAttempt.score}`}
          </p>
          {myAttempt.status === 'IN_PROGRESS' && (
            <>
              <AttemptQuestions assessmentId={assessment.id} attemptId={myAttempt.id} />
              <ConfirmActionButton label="Submit attempt" confirmMessage="Submit your attempt? You will not be able to change your answers afterwards." onConfirm={submitAttempt} />
            </>
          )}
        </div>
      ) : (
        assessment.status === 'OPEN' && (
          <div style={{ marginTop: 10 }}>
            <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }} disabled={starting} onClick={start}>
              {starting ? 'Starting…' : 'Start attempt'}
            </button>
          </div>
        )
      )}
      {error && <p style={{ fontSize: 12, color: 'var(--flag)', marginTop: 6 }}>{error}</p>}
    </div>
  );
}

export default function StudentCourseDetailPage() {
  const { enrollmentId } = useParams<{ enrollmentId: string }>();
  const enrollment = useApiQuery<EnrollmentResponse>(enrollmentId ? `/enrollment/enrollments/${enrollmentId}` : null);
  const section = useApiQuery<CourseSectionResponse>(enrollment.data ? `/enrollment/course-sections/${enrollment.data.sectionId}` : null, [enrollment.data]);
  const course = useApiQuery<CourseResponse>(section.data ? `/catalog/courses/${section.data.courseId}` : null, [section.data]);
  const modules = useApiQuery<CourseModuleResponse[]>(
    section.data ? `/learning/modules?sectionId=${section.data.id}&tenantId=${section.data.tenantId}` : null, [section.data],
  );
  const assignments = useApiQuery<LearningAssignmentResponse[]>(
    section.data ? `/learning/assignments?sectionId=${section.data.id}&tenantId=${section.data.tenantId}` : null, [section.data],
  );
  const assessments = useApiQuery<AssessmentResponse[]>(
    section.data ? `/assessment/assessments?sectionId=${section.data.id}&tenantId=${section.data.tenantId}` : null, [section.data],
  );

  if (enrollment.loading || section.loading || course.loading) {
    return <Skeleton height={200} />;
  }
  if (enrollment.error) {
    return <div className="card"><ErrorState message="Could not load this course." onRetry={enrollment.refetch} /></div>;
  }

  return (
    <div>
      <p style={{ marginBottom: 16 }}>
        <Link to="/app/student/courses" style={{ fontSize: 13 }}>&larr; Back to my courses</Link>
      </p>
      <PageHeader
        title={course.data ? `${course.data.code} — ${course.data.title}` : 'Course'}
        subtitle={section.data ? `Section ${section.data.sectionCode}` : undefined}
      />

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>Modules & Lessons</h2>
      {modules.loading && <Skeleton height={80} />}
      {modules.error && <div className="card"><ErrorState message="Could not load modules." onRetry={modules.refetch} /></div>}
      {modules.data && modules.data.length === 0 && <div className="card"><p style={{ color: 'var(--ink-soft)' }}>No modules published yet.</p></div>}
      {modules.data?.map((m) => enrollment.data && <ModuleSection key={m.id} module={m} studentId={enrollment.data.studentId} />)}

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Assignments</h2>
      {assignments.loading && <Skeleton height={80} />}
      {assignments.data && assignments.data.length === 0 && <div className="card"><p style={{ color: 'var(--ink-soft)' }}>No assignments yet.</p></div>}
      {assignments.data?.map((a) => <AssignmentRow key={a.id} assignment={a} />)}

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Assessments</h2>
      {assessments.loading && <Skeleton height={80} />}
      {assessments.data && assessments.data.length === 0 && <div className="card"><p style={{ color: 'var(--ink-soft)' }}>No assessments yet.</p></div>}
      {assessments.data?.map((a) => <AssessmentCard key={a.id} assessment={a} />)}
    </div>
  );
}
