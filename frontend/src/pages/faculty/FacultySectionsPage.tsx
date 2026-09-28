import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import { apiFetch } from '../../api/client';
import type { FacultyAssignmentResponse, CourseSectionResponse, CourseResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton } from '../../components/ui/EmptyState';

interface EnrichedSection {
  assignment: FacultyAssignmentResponse;
  section: CourseSectionResponse | null;
  course: CourseResponse | null;
}

export default function FacultySectionsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;

  const { data: assignments, loading, error, refetch } = useApiQuery<FacultyAssignmentResponse[]>(
    facultyId && tenantId ? `/enrollment/faculty-assignments?facultyId=${facultyId}&tenantId=${tenantId}` : null,
  );

  const [enriched, setEnriched] = useState<EnrichedSection[] | null>(null);
  const [enrichLoading, setEnrichLoading] = useState(false);

  useEffect(() => {
    const active = assignments?.filter((a) => a.active) ?? [];
    if (!assignments) return;
    if (active.length === 0) {
      setEnriched([]);
      return;
    }
    let cancelled = false;
    setEnrichLoading(true);
    Promise.all(
      active.map(async (assignment) => {
        const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${assignment.sectionId}`).catch(() => null);
        const course = section ? await apiFetch<CourseResponse>(`/catalog/courses/${section.courseId}`).catch(() => null) : null;
        return { assignment, section, course };
      }),
    ).then((rows) => {
      if (!cancelled) setEnriched(rows);
    }).finally(() => {
      if (!cancelled) setEnrichLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [assignments]);

  const columns: Column<EnrichedSection>[] = [
    { key: 'course', header: 'Course', render: (r) => r.course ? `${r.course.code} — ${r.course.title}` : '—' },
    { key: 'section', header: 'Section', render: (r) => <span className="mono">{r.section?.sectionCode ?? '—'}</span> },
    { key: 'role', header: 'Role', render: (r) => r.assignment.role },
    { key: 'capacity', header: 'Capacity', render: (r) => r.section?.capacity ?? '—' },
    { key: 'status', header: 'Status', render: (r) => r.section ? <StatusBadge value={r.section.status} /> : '—' },
  ];

  return (
    <div>
      <PageHeader title="My Sections" subtitle="Sections you are actively assigned to teach" />
      {(loading || enrichLoading) && !enriched ? (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <Skeleton height={20} />
          <Skeleton height={20} />
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={enriched}
          loading={false}
          error={error}
          onRetry={refetch}
          emptyTitle="No sections assigned"
          emptyDescription="You are not currently assigned to teach any section."
          rowKey={(r) => r.assignment.id}
        />
      )}
    </div>
  );
}
