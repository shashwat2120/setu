import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  GuardianGrantResponse, StudentResponse, EnrollmentResponse, PageResponse, CourseSectionResponse, CourseResponse,
  AttendancePercentageResponse, AssessmentResponse, AssessmentComponentResponse, GradeResponse,
  StudentAccountResponse, StudentChargeResponse, RiskScoreResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState, EmptyState } from '../../components/ui/EmptyState';

interface Row {
  enrollment: EnrollmentResponse;
  section: CourseSectionResponse | null;
  course: CourseResponse | null;
  percentage: AttendancePercentageResponse | null;
}

function ComponentGrade({ enrollmentId, component }: { enrollmentId: string; component: AssessmentComponentResponse }) {
  const grade = useApiQuery<GradeResponse>(`/assessment/grades?enrollmentId=${enrollmentId}&componentId=${component.id}`);
  return (
    <tr>
      <td data-label="Component"><span className="mono">{component.code}</span></td>
      <td data-label="Grade">
        {grade.loading ? <Skeleton height={16} width={60} /> : grade.data ? (
          <span className="mono">{grade.data.rawMarks} / {grade.data.gradeCode}</span>
        ) : <span style={{ color: 'var(--ink-soft)' }}>Not graded yet</span>}
      </td>
    </tr>
  );
}

function AssessmentGradeBlock({ assessment, enrollmentId, studentId }: { assessment: AssessmentResponse; enrollmentId: string; studentId: string }) {
  const components = useApiQuery<AssessmentComponentResponse[]>(`/assessment/components?assessmentId=${assessment.id}&guardianForStudentId=${studentId}`);
  return (
    <div className="card" style={{ marginBottom: 10, padding: 0, overflow: 'hidden' }}>
      <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--rule)', display: 'flex', justifyContent: 'space-between' }}>
        <strong style={{ fontSize: 14 }}>{assessment.title}</strong>
        <StatusBadge value={assessment.status} />
      </div>
      {components.data && components.data.length > 0 && (
        <table className="data-table">
          <tbody>{components.data.map((c) => <ComponentGrade key={c.id} enrollmentId={enrollmentId} component={c} />)}</tbody>
        </table>
      )}
    </div>
  );
}

function GradesForSection({ enrollmentId, sectionId, tenantId, studentId }: { enrollmentId: string; sectionId: string; tenantId: string; studentId: string }) {
  const assessments = useApiQuery<AssessmentResponse[]>(`/assessment/assessments?sectionId=${sectionId}&tenantId=${tenantId}&guardianForStudentId=${studentId}`);
  if (assessments.loading) return <Skeleton height={40} />;
  if (!assessments.data || assessments.data.length === 0) return <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>No assessments yet.</p>;
  return <>{assessments.data.map((a) => <AssessmentGradeBlock key={a.id} assessment={a} enrollmentId={enrollmentId} studentId={studentId} />)}</>;
}

export default function GuardianStudentPage() {
  const { studentId } = useParams<{ studentId: string }>();
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'GUARDIAN');

  const grants = useApiQuery<GuardianGrantResponse[]>(tenantId ? `/guardian-access/grants/mine?tenantId=${tenantId}` : null);
  const grant = grants.data?.find((g) => g.studentId === studentId && g.status === 'ACTIVE') ?? null;
  const has = (p: string) => grant?.permissions.includes(p) ?? false;

  const profile = useApiQuery<StudentResponse>(has('VIEW_ACADEMIC_STATUS') && studentId ? `/directory/students/${studentId}` : null);
  const enrollments = useApiQuery<PageResponse<EnrollmentResponse>>(
    has('VIEW_SCHEDULE') && studentId ? `/enrollment/enrollments?studentId=${studentId}&size=100` : null,
  );
  const account = useApiQuery<StudentAccountResponse>(
    has('VIEW_FINANCE') && tenantId && studentId ? `/finance/accounts?tenantId=${tenantId}&studentId=${studentId}` : null,
  );
  const charges = useApiQuery<StudentChargeResponse[]>(has('VIEW_FINANCE') && studentId ? `/finance/charges?studentId=${studentId}` : null);
  const risk = useApiQuery<RiskScoreResponse>(has('VIEW_RISK_SCORE') && studentId ? `/risk/scores/current?studentId=${studentId}` : null);

  const [rows, setRows] = useState<Row[] | null>(null);

  useEffect(() => {
    if (!enrollments.data) return;
    const active = enrollments.data.content.filter((e) => e.status === 'ENROLLED');
    if (active.length === 0) { setRows([]); return; }
    let cancelled = false;
    Promise.all(active.map(async (enrollment) => {
      const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${enrollment.sectionId}?guardianForStudentId=${studentId}`).catch(() => null);
      const course = section ? await apiFetch<CourseResponse>(`/catalog/courses/${section.courseId}`).catch(() => null) : null;
      const percentage = has('VIEW_ATTENDANCE')
        ? await apiFetch<AttendancePercentageResponse>(`/attendance/enrollments/${enrollment.id}/percentage`).catch(() => null)
        : null;
      return { enrollment, section, course, percentage };
    })).then((result) => { if (!cancelled) setRows(result); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enrollments.data]);

  const attendanceColumns: Column<Row>[] = [
    { key: 'course', header: 'Course', render: (r) => (r.course ? `${r.course.code} — ${r.course.title}` : r.section?.sectionCode ?? '—') },
    {
      key: 'attendance',
      header: 'Attendance',
      render: (r) => (r.percentage ? <span className="mono">{r.percentage.percentage.toFixed(1)}%</span> : <span style={{ color: 'var(--ink-soft)' }}>—</span>),
    },
  ];

  if (grants.loading) return <Skeleton height={200} />;
  if (grants.error) return <div className="card"><ErrorState message="Could not load your access grants." onRetry={grants.refetch} /></div>;
  if (!grant) {
    return (
      <div>
        <Link to="/app/guardian" className="btn btn-ghost" style={{ marginBottom: 16, display: 'inline-block' }}>&larr; Back</Link>
        <EmptyState title="No active access" description="You do not currently have an active, granted access to this student." />
      </div>
    );
  }

  return (
    <div>
      <Link to="/app/guardian" className="btn btn-ghost" style={{ marginBottom: 16, display: 'inline-block' }}>&larr; Back</Link>
      <PageHeader
        title={profile.data?.fullName ?? `Student ${studentId?.slice(0, 8)}…`}
        subtitle={`Scoped to: ${grant.permissions.join(', ') || 'no permissions'}`}
      />

      {has('VIEW_RISK_SCORE') && (
        <div className="card-grid" style={{ marginBottom: 24 }}>
          <div className="card">
            <div className="stat-label">Risk band</div>
            {risk.loading ? <Skeleton height={28} width={80} /> : risk.data ? (
              <div style={{ marginTop: 6 }}><StatusBadge value={risk.data.band} /></div>
            ) : <div style={{ marginTop: 6, color: 'var(--ink-soft)' }}>Not yet calculated</div>}
          </div>
        </div>
      )}

      {has('VIEW_FINANCE') && (
        <>
          <h2 style={{ fontSize: 16, marginBottom: 10 }}>Finance</h2>
          {account.loading ? <Skeleton height={60} /> : account.data ? (
            <div className="card" style={{ marginBottom: 20, maxWidth: 260 }}>
              <div className="stat-label">Account balance</div>
              <div className="mono" style={{ marginTop: 6, fontSize: 20 }}>{account.data.balance} {account.data.currency}</div>
            </div>
          ) : <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 20 }}>No finance account on record.</p>}
          {charges.data && charges.data.length > 0 && (
            <DataTable
              columns={[
                { key: 'amount', header: 'Amount', render: (c: StudentChargeResponse) => <span className="mono">{c.amount} {c.currency}</span> },
                { key: 'due', header: 'Due', render: (c: StudentChargeResponse) => c.dueDate },
                { key: 'status', header: 'Status', render: (c: StudentChargeResponse) => <StatusBadge value={c.status} /> },
              ]}
              rows={charges.data}
              loading={false}
              error={null}
              emptyTitle="No charges"
              rowKey={(c) => c.id}
            />
          )}
        </>
      )}

      {has('VIEW_SCHEDULE') && (
        <>
          <h2 style={{ fontSize: 16, margin: '24px 0 10px' }}>Schedule &amp; attendance</h2>
          <DataTable
            columns={attendanceColumns}
            rows={rows}
            loading={!rows}
            error={null}
            emptyTitle="Not enrolled"
            emptyDescription="This student has no active enrollments."
            rowKey={(r) => r.enrollment.id}
          />
        </>
      )}

      {has('VIEW_GRADES') && rows && rows.length > 0 && (
        <>
          <h2 style={{ fontSize: 16, margin: '24px 0 10px' }}>Grades</h2>
          {rows.map((r) => (
            <div key={r.enrollment.id} style={{ marginBottom: 16 }}>
              <p style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>{r.course ? `${r.course.code} — ${r.course.title}` : r.section?.sectionCode}</p>
              <GradesForSection enrollmentId={r.enrollment.id} sectionId={r.enrollment.sectionId} tenantId={r.enrollment.tenantId} studentId={studentId ?? ''} />
            </div>
          ))}
        </>
      )}

      {!has('VIEW_SCHEDULE') && (has('VIEW_GRADES') || has('VIEW_ATTENDANCE')) && (
        <EmptyState title="Courses are not shared yet" description="Grades and attendance are shown course by course. The college needs to allow view schedule on this access before they can appear." />
      )}

      {!has('VIEW_SCHEDULE') && !has('VIEW_GRADES') && !has('VIEW_ATTENDANCE') && !has('VIEW_FINANCE') && !has('VIEW_RISK_SCORE') && (
        <EmptyState title="No viewable data categories" description="This grant does not currently include any data category this view can show." />
      )}
    </div>
  );
}
