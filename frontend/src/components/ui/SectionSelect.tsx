import { useApiQuery } from '../../api/useApiQuery';
import type { CourseSectionResponse, CourseResponse, PageResponse } from '../../api/types';

/** Shared section picker used by the admin Enrollment/Attendance/Assessments pages — real sections/courses, never invented. */
export function useSectionOptions(tenantId: string | null) {
  const sections = useApiQuery<PageResponse<CourseSectionResponse>>(
    tenantId ? `/enrollment/course-sections?tenantId=${tenantId}&size=200` : null,
  );
  const courses = useApiQuery<PageResponse<CourseResponse>>(
    tenantId ? `/catalog/courses?tenantId=${tenantId}&size=200` : null,
  );
  const courseNameFor = (courseId: string) => courses.data?.content.find((c) => c.id === courseId)?.title ?? courseId;
  return { sections: sections.data?.content ?? null, loading: sections.loading || courses.loading, courseNameFor };
}

export function SectionSelect({
  sections, courseNameFor, value, onChange,
}: {
  sections: CourseSectionResponse[] | null;
  courseNameFor: (courseId: string) => string;
  value: string;
  onChange: (sectionId: string) => void;
}) {
  return (
    <div style={{ marginBottom: 16, maxWidth: 420 }}>
      <label htmlFor="section-select" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
        Section
      </label>
      <select
        id="section-select"
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
        {sections?.map((s) => (
          <option key={s.id} value={s.id}>
            {courseNameFor(s.courseId)} — {s.sectionCode}
          </option>
        ))}
      </select>
    </div>
  );
}
