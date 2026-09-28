/**
 * The {@link com.setu.shared.identity.PersonDirectory} contract. See the
 * parent {@code shared} package's javadoc for why this lives here rather
 * than being module-owned.
 * <p>
 * Added Sprint 5C Day 6 to break a genuine module dependency cycle: several
 * directory-owned services (student/faculty/guardian profiles) need to
 * resolve a person by id, and identity's own {@code GuardianDelegationAdapter}
 * already depends on {@code directory.application.DirectoryService} the
 * other way. Depending on {@code identity.application.PersonService}
 * directly from {@code directory} would have closed a
 * directory&nbsp;&#8596;&nbsp;identity cycle, which
 * {@code ApplicationModules.verify()} correctly rejects. Routing through
 * this shared contract instead — implemented by identity, consumed by
 * directory/guardianaccess — keeps the dependency graph a DAG, the same
 * fix already applied to {@link com.setu.shared.authorization.
 * GuardianDelegationChecker} for an analogous cycle.
 */
package com.setu.shared.identity;
