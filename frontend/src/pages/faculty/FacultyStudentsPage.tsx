import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import { apiFetch } from '../../api/client';
import type { FacultyAssignmentResponse, CourseSectionResponse, EnrollmentResponse, StudentResponse, PageResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';

interface StudentRow {
  enrollment: EnrollmentResponse;
  student: StudentResponse | null;
  sectionCode: string;
}

export default function FacultyStudentsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;

  const { data: assignments } = useApiQuery<FacultyAssignmentResponse[]>(
    facultyId && tenantId ? `/enrollment/faculty-assignments?facultyId=${facultyId}&tenantId=${tenantId}` : null,
  );

  const [rows, setRows] = useState<StudentRow[] | null>(null);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    if (!assignments || !tenantId) return;
    const active = assignments.filter((a) => a.active);
    if (active.length === 0) {
      setRows([]);
      return;
    }
    let cancelled = false;
    setError(null);
    (async () => {
      try {
        const perSection = await Promise.all(
          active.map(async (a) => {
            const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${a.sectionId}`).catch(() => null);
            const enrollments = await apiFetch<PageResponse<EnrollmentResponse>>(
              `/enrollment/enrollments/section/${a.sectionId}?tenantId=${tenantId}&size=100`,
            ).catch(() => null);
            const active2 = enrollments?.content.filter((e) => e.status === 'ENROLLED') ?? [];
            const withStudents = await Promise.all(
              active2.map(async (enrollment) => ({
                enrollment,
                student: await apiFetch<StudentResponse>(`/directory/students/${enrollment.studentId}`).catch(() => null),
                sectionCode: section?.sectionCode ?? '—',
              })),
            );
            return withStudents;
          }),
        );
        if (!cancelled) setRows(perSection.flat());
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e : new Error(String(e)));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [assignments, tenantId]);

  const columns: Column<StudentRow>[] = [
    { key: 'name', header: 'Name', render: (r) => r.student?.fullName ?? '—' },
    { key: 'admissionNo', header: 'Admission No.', render: (r) => <span className="mono">{r.student?.admissionNo ?? '—'}</span> },
    { key: 'section', header: 'Section', render: (r) => <span className="mono">{r.sectionCode}</span> },
    { key: 'status', header: 'Enrollment', render: (r) => <StatusBadge value={r.enrollment.status} /> },
  ];

  return (
    <div>
      <PageHeader title="Students" subtitle="Enrolled across your assigned sections" />
      {!rows && !error ? (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <Skeleton height={20} />
          <Skeleton height={20} />
        </div>
      ) : error ? (
        <div className="card"><ErrorState message="Could not load your students." /></div>
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          loading={false}
          error={null}
          emptyTitle="No students found"
          emptyDescription="No students are currently enrolled in your assigned sections."
          rowKey={(r) => r.enrollment.id}
        />
      )}
    </div>
  );
}
