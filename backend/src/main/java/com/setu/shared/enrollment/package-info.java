/**
 * The {@link com.setu.shared.enrollment.CourseSectionDirectory} contract.
 * See the parent {@code shared} package's javadoc for why this lives here
 * rather than being module-owned.
 * <p>
 * Added Sprint 5C Day 7 to break a genuine module dependency cycle:
 * {@code catalog.meeting_patterns.section_id} is a real FK into
 * {@code enrollment.course_sections} (V29's own header comment notes it
 * migrates after enrollment.course_sections precisely because of this FK),
 * so {@code catalog.application.MeetingPatternService}
 * needs to validate a meeting pattern's section tenant against
 * enrollment-owned data. Meanwhile {@code enrollment.application.
 * CourseSectionService} separately needs catalog's course/period/room data
 * the other way (a direct, one-directional dependency, the same established
 * pattern already used by {@code enrollment.StudentProgramService} ->
 * {@code catalog.ProgramService}/{@code catalog.CurriculumService}).
 * Depending on {@code enrollment.application.CourseSectionService} directly
 * from catalog would have closed a
 * catalog&nbsp;&#8596;&nbsp;enrollment cycle, which
 * {@code ApplicationModules.verify()} correctly rejects. Routing through
 * this shared contract instead — implemented by enrollment, consumed by
 * catalog — keeps the dependency graph a DAG (enrollment -&gt; catalog
 * one-way for course/period/room/curriculum/program lookups, catalog -&gt;
 * shared &lt;- enrollment for this one section-tenant check), the same fix
 * already applied to {@link com.setu.shared.directory.FacultyDirectory} for
 * an analogous case.
 */
package com.setu.shared.enrollment;
