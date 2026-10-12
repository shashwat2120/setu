import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  EnrollmentResponse, PageResponse, CourseSectionResponse, CourseResponse, AssessmentResponse,
  AssessmentComponentResponse, GradeResponse, TranscriptResponse, DegreeProgressResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState, EmptyState } from '../../components/ui/EmptyState';

function TranscriptAndProgress({ studentId, tenantId }: { studentId: string; tenantId: string }) {
  const transcript = useApiQuery<TranscriptResponse>(`/record/transcript?studentId=${studentId}&tenantId=${tenantId}`);
  const progress = useApiQuery<DegreeProgressResponse>(`/record/degree-progress?studentId=${studentId}&tenantId=${tenantId}`);

  return (
    <section className="card" style={{ marginBottom: 24 }}>
      <h2 style={{ fontSize: 16, marginBottom: 10 }}>Transcript &amp; degree progress</h2>
      <div className="card-grid" style={{ marginBottom: 16 }}>
        <div className="card">
          <div className="stat-label">GPA (credit-weighted, to date)</div>
          <div className="mono" style={{ marginTop: 6, fontSize: 20 }}>
            {transcript.loading ? <Skeleton height={24} width={60} /> : transcript.data?.gpa ?? '—'}
          </div>
        </div>
        <div className="card">
          <div className="stat-label">Degree progress</div>
          {progress.loading ? <Skeleton height={24} width={100} /> : progress.data?.hasActiveProgram ? (
            <div style={{ marginTop: 6 }}>
              <span className="mono" style={{ fontSize: 20 }}>{progress.data.percentage}%</span>
              <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginTop: 2 }}>
                {progress.data.creditsCompleted} / {progress.data.creditsRequired} credits
              </p>
            </div>
          ) : <div style={{ marginTop: 6, color: 'var(--ink-soft)' }}>No active program</div>}
        </div>
      </div>
      {transcript.data && transcript.data.lines.length > 0 && (
        <table className="data-table">
          <thead><tr><th scope="col">Course</th><th scope="col">Credits</th><th scope="col">Status</th><th scope="col">Grades</th><th scope="col">Course grade point</th></tr></thead>
          <tbody>
            {transcript.data.lines.map((l) => (
              <tr key={l.enrollmentId}>
                <td data-label="Course">{l.courseCode ? `${l.courseCode} — ${l.courseTitle}` : l.sectionCode}</td>
                <td data-label="Credits">{l.credits ?? '—'}</td>
                <td data-label="Status"><StatusBadge value={l.enrollmentStatus} /></td>
                <td data-label="Grades">
                  {l.grades.length > 0
                    ? l.grades.map((g) => <span key={g.componentId} className="mono" style={{ marginRight: 8 }}>{g.rawMarks}/{g.gradeCode}</span>)
                    : <span style={{ color: 'var(--ink-soft)' }}>No grades yet</span>}
                </td>
                <td data-label="Course grade point" className="mono">{l.courseGradePoint ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

interface EnrolledSection {
  enrollment: EnrollmentResponse;
  section: CourseSectionResponse;
  course: CourseResponse | null;
}

function ComponentGrade({ enrollmentId, component }: { enrollmentId: string; component: AssessmentComponentResponse }) {
  const grade = useApiQuery<GradeResponse>(`/assessment/grades?enrollmentId=${enrollmentId}&componentId=${component.id}`);
  return (
    <tr>
      <td data-label="Component"><span className="mono">{component.code}</span></td>
      <td data-label="Max marks">{component.maxMarks}</td>
      <td data-label="Grade">
        {grade.loading ? <Skeleton height={16} width={80} /> : grade.data ? (
          <span className="mono">{grade.data.rawMarks} / {grade.data.gradeCode} ({grade.data.gradePoint})</span>
        ) : <span style={{ color: 'var(--ink-soft)' }}>Not graded yet</span>}
      </td>
      <td data-label="Status">{grade.data && <StatusBadge value={grade.data.status} />}</td>
    </tr>
  );
}

function AssessmentGrades({ enrollmentId, sectionId, tenantId }: { enrollmentId: string; sectionId: string; tenantId: string }) {
  const assessments = useApiQuery<AssessmentResponse[]>(`/assessment/assessments?sectionId=${sectionId}&tenantId=${tenantId}`);
  return (
    <>
      {assessments.loading && <Skeleton height={60} />}
      {assessments.error && <ErrorState message="Could not load assessments." onRetry={assessments.refetch} />}
      {assessments.data?.map((a) => <AssessmentGradeBlock key={a.id} assessment={a} enrollmentId={enrollmentId} />)}
    </>
  );
}

function AssessmentGradeBlock({ assessment, enrollmentId }: { assessment: AssessmentResponse; enrollmentId: string }) {
  const components = useApiQuery<AssessmentComponentResponse[]>(`/assessment/components?assessmentId=${assessment.id}`);
  return (
    <div className="card" style={{ marginBottom: 12, padding: 0, overflow: 'hidden' }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--rule)', display: 'flex', justifyContent: 'space-between' }}>
        <strong>{assessment.title}</strong>
        <StatusBadge value={assessment.status} />
      </div>
      {components.data && components.data.length > 0 ? (
        <table className="data-table">
          <thead><tr><th scope="col">Component</th><th scope="col">Max marks</th><th scope="col">Grade</th><th scope="col">Status</th></tr></thead>
          <tbody>{components.data.map((c) => <ComponentGrade key={c.id} enrollmentId={enrollmentId} component={c} />)}</tbody>
        </table>
      ) : (
        <div style={{ padding: 16 }}><EmptyState title="No components" /></div>
      )}
    </div>
  );
}

export default function StudentGradesPage() {
  const { user } = useAuth();
  const studentId = user?.studentId ?? null;
  const tenantId = tenantForRole(user, 'STUDENT');

  const enrollments = useApiQuery<PageResponse<EnrollmentResponse>>(studentId ? `/enrollment/enrollments?studentId=${studentId}&size=100` : null);
  const [sections, setSections] = useState<EnrolledSection[] | null>(null);

  useEffect(() => {
    if (!enrollments.data) return;
    const active = enrollments.data.content.filter((e) => e.status === 'ENROLLED');
    if (active.length === 0) {
      setSections([]);
      return;
    }
    let cancelled = false;
    Promise.all(active.map(async (enrollment) => {
      const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${enrollment.sectionId}`);
      const course = await apiFetch<CourseResponse>(`/catalog/courses/${section.courseId}`).catch(() => null);
      return { enrollment, section, course };
    })).then((result) => { if (!cancelled) setSections(result); });
    return () => { cancelled = true; };
  }, [enrollments.data]);

  return (
    <div>
      <PageHeader title="Grades" subtitle="Component-level grades — a correction always adds a new version, it never overwrites the prior one." />
      {studentId && tenantId && <TranscriptAndProgress studentId={studentId} tenantId={tenantId} />}
      {!sections && <Skeleton height={100} />}
      {sections && sections.length === 0 && <div className="card"><EmptyState title="No grades yet" description="You are not enrolled in any sections yet." /></div>}
      {sections?.map((s) => (
        <div key={s.enrollment.id} style={{ marginBottom: 24 }}>
          <h2 style={{ fontSize: 16, marginBottom: 10 }}>
            {s.course ? `${s.course.code} — ${s.course.title}` : s.section.sectionCode}
          </h2>
          <AssessmentGrades enrollmentId={s.enrollment.id} sectionId={s.section.id} tenantId={s.section.tenantId} />
        </div>
      ))}
    </div>
  );
}
