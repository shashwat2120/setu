import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  EnrollmentResponse, PageResponse, CourseSectionResponse, CourseResponse, AssessmentResponse,
  AssessmentComponentResponse, GradeResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState, EmptyState } from '../../components/ui/EmptyState';

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
