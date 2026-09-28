import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { EnrollmentResponse, CourseSectionResponse, CourseResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { apiFetch } from '../../api/client';
import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';

interface EnrichedRow {
  enrollment: EnrollmentResponse;
  section: CourseSectionResponse | null;
  course: CourseResponse | null;
}

export default function StudentCoursesPage() {
  const { user } = useAuth();
  const studentId = user?.studentId ?? null;
  const tenantId = tenantForRole(user, 'STUDENT');
  const navigate = useNavigate();

  const { data, loading, error, refetch, page, setPage } = usePagedQuery<EnrollmentResponse>(
    studentId ? '/enrollment/enrollments' : null,
    studentId ? { studentId } : {},
  );

  const [rows, setRows] = useState<EnrichedRow[] | null>(null);
  const [openSections, setOpenSections] = useState<CourseSectionResponse[]>([]);
  const [notice, setNotice] = useState('');
  const [busySection, setBusySection] = useState('');

  useEffect(() => {
    if (!tenantId) return;
    apiFetch<{ content: CourseSectionResponse[] }>('/enrollment/course-sections/open-for-registration?tenantId=' + tenantId + '&size=100')
      .then((result) => setOpenSections(result.content))
      .catch(() => setOpenSections([]));
  }, [tenantId]);

  async function enroll(sectionId: string) {
    if (!studentId) return;
    setBusySection(sectionId); setNotice('');
    try {
      const result = await apiFetch<{ status?: string; position?: number }>('/enrollment/enrollments', { method: 'POST', body: { studentId, sectionId } });
      setNotice(typeof result.position === 'number' ? `You were added to the waitlist at position ${result.position}.` : 'Enrollment confirmed.');
      refetch();
    } catch (e) { setNotice(e instanceof ApiError ? e.message : 'Enrollment could not be completed.'); }
    finally { setBusySection(''); }
  }

  useEffect(() => {
    if (!data) return;
    let cancelled = false;
    Promise.all(
      data.content.map(async (enrollment) => {
        const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${enrollment.sectionId}`).catch(() => null);
        const course = section ? await apiFetch<CourseResponse>(`/catalog/courses/${section.courseId}`).catch(() => null) : null;
        return { enrollment, section, course };
      }),
    ).then((result) => {
      if (!cancelled) setRows(result);
    });
    return () => {
      cancelled = true;
    };
  }, [data]);

  const columns: Column<EnrichedRow>[] = [
    { key: 'course', header: 'Course', render: (r) => (r.course ? `${r.course.code} — ${r.course.title}` : '—') },
    { key: 'section', header: 'Section', render: (r) => <span className="mono">{r.section?.sectionCode ?? '—'}</span> },
    { key: 'status', header: 'Enrollment', render: (r) => <StatusBadge value={r.enrollment.status} /> },
  ];

  return (
    <div>
      <PageHeader title="My Courses" subtitle={data ? `${data.totalElements} enrollments` : undefined} />
      <section className="card" style={{ marginBottom: 18 }}>
        <h2 style={{ fontSize: 17, marginBottom: 8 }}>Register for a section</h2>
        <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>Setu checks prerequisites, schedule conflicts, capacity, credit limits, and holds when you submit.</p>
        {notice && <p role="status" style={{ marginBottom: 10 }}>{notice}</p>}
        {openSections.length === 0 ? <p style={{ color: 'var(--ink-soft)' }}>No open sections are available right now.</p> : openSections.map((s) => <div key={s.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 0', borderTop: '1px solid var(--line)' }}><span className="mono">{s.sectionCode}</span><button className="btn btn-primary" type="button" disabled={!!busySection} onClick={() => enroll(s.id)}>{busySection === s.id ? 'Submitting…' : 'Enroll'}</button></div>)}
      </section>
      <DataTable
        columns={columns}
        rows={rows}
        loading={loading || (!!data && !rows)}
        error={error}
        onRetry={refetch}
        emptyTitle="No courses"
        emptyDescription="You are not enrolled in any sections yet."
        rowKey={(r) => r.enrollment.id}
        onRowClick={(r) => navigate(`/app/student/courses/${r.enrollment.id}`)}
      />
      {data && <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />}
    </div>
  );
}
