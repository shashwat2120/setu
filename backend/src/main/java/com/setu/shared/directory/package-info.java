/**
 * The {@link com.setu.shared.directory.FacultyDirectory} contract. See the
 * parent {@code shared} package's javadoc for why this lives here rather
 * than being module-owned.
 * <p>
 * Added Sprint 5C Day 7 to avoid a potential directory &#8596; enrollment
 * cycle before it could form: {@code enrollment.application.
 * FacultyAssignmentService} needs to validate a faculty id's tenant, and
 * {@code enrollment.application.StudentProgramService} already depends
 * directly on {@code directory.application.StudentProfileService} (a plain
 * {@code @NamedInterface}, safe today only because directory has no edge
 * back into enrollment). Adding a second, direct
 * enrollment&nbsp;-&gt;&nbsp;directory edge for faculty risked exactly the
 * kind of accidental coupling that turns into a real cycle the moment
 * directory ever needs anything from enrollment. Routing through this
 * shared contract instead — implemented by directory, consumed by
 * enrollment — keeps the dependency graph a DAG, the same fix already
 * applied to {@link com.setu.shared.identity.PersonDirectory} and
 * {@link com.setu.shared.academic.AcademicCalendarPolicy} for analogous
 * cases.
 */
package com.setu.shared.directory;
