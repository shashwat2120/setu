import { useApiQuery } from '../../api/useApiQuery';
import type { CourseResponse, CourseSectionResponse } from '../../api/types';

/** A section as people know it, "CS101 — Introduction to Programming (CS101-A)", from its id. Falls back to a short id while loading. */
export function SectionLabel({ sectionId }: { sectionId: string }) {
  const section = useApiQuery<CourseSectionResponse>(`/enrollment/course-sections/${sectionId}`);
  const course = useApiQuery<CourseResponse>(section.data ? `/catalog/courses/${section.data.courseId}` : null);
  if (!section.data) return <>{`Section ${sectionId.slice(0, 8)}…`}</>;
  return <>{course.data ? `${course.data.code} — ${course.data.title} (${section.data.sectionCode})` : section.data.sectionCode}</>;
}
