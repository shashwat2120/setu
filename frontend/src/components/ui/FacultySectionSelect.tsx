import { useEffect, useState } from 'react';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { FacultyAssignmentResponse, CourseSectionResponse, CourseResponse } from '../../api/types';

interface EnrichedSection {
  sectionId: string;
  section: CourseSectionResponse | null;
  course: CourseResponse | null;
}

/** Shared "which of MY assigned sections" picker used by every faculty page that scopes to a real section. */
export function useFacultySectionOptions(tenantId: string | null, facultyId: string | null) {
  const { data: assignments, loading: assignmentsLoading } = useApiQuery<FacultyAssignmentResponse[]>(
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
      active.map(async (a) => {
        const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${a.sectionId}`).catch(() => null);
        const course = section ? await apiFetch<CourseResponse>(`/catalog/courses/${section.courseId}`).catch(() => null) : null;
        return { sectionId: a.sectionId, section, course };
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

  const courseNameFor = (courseId: string) => enriched?.find((e) => e.course?.id === courseId)?.course?.title ?? courseId;
  return { sections: enriched, loading: assignmentsLoading || enrichLoading, courseNameFor };
}

export function FacultySectionSelect({
  sections, value, onChange,
}: {
  sections: EnrichedSection[] | null;
  value: string;
  onChange: (sectionId: string) => void;
}) {
  return (
    <div style={{ marginBottom: 16, maxWidth: 420 }}>
      <label htmlFor="faculty-section-select" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
        Section
      </label>
      <select
        id="faculty-section-select"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        style={{
          width: '100%',
          fontFamily: 'var(--font-body)',
          fontSize: 14,
          padding: '9px 12px',
          border: '1px solid var(--rule)',
          borderRadius: 4,
          background: 'var(--bg-elev)',
          color: 'var(--ink)',
        }}
      >
        <option value="">Select a section…</option>
        {sections?.map((e) => (
          <option key={e.sectionId} value={e.sectionId}>
            {e.course ? `${e.course.code} — ${e.course.title}` : e.sectionId} ({e.section?.sectionCode ?? '—'})
          </option>
        ))}
      </select>
    </div>
  );
}
