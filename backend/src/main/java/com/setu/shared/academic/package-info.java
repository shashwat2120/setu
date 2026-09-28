/**
 * The {@link com.setu.shared.academic.AcademicCalendarPolicy} contract. See
 * the parent {@code shared} package's javadoc for why this lives here
 * rather than being module-owned.
 * <p>
 * Added Sprint 5C Day 6 to break a genuine module dependency cycle:
 * {@code institution.CollegeService}'s archive validation
 * (docs/state-machines.md #4 — "cannot archive a tenant with any ACTIVE
 * academic_periods mid-term") needs catalog's academic-period data, while
 * {@code catalog.ProgramService} (Day 6) separately needs institution's
 * department data for its own tenant validation. Depending on
 * {@code catalog.application.AcademicPeriodService} directly from
 * institution would have closed an
 * institution&nbsp;&#8596;&nbsp;catalog cycle, which
 * {@code ApplicationModules.verify()} correctly rejects. Routing through
 * this shared contract instead — implemented by catalog, consumed by
 * institution — keeps the dependency graph a DAG (catalog -&gt;
 * institution one-way, institution -&gt; shared &lt;- catalog for this one
 * check), the same fix already applied to
 * {@link com.setu.shared.identity.PersonDirectory} for an analogous cycle.
 */
package com.setu.shared.academic;
