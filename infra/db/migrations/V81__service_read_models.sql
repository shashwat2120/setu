-- Each service that sits on top of Academic (learning, assessment, attendance) keeps its OWN local copy of the Identity and Academic data it needs
-- to authorize and validate a request (Sprint 6A.5, docs/contracts/extracted-services.md). The same set of tables, once per service schema, so that
-- when the schemas become separate databases (6B.2) nothing is shared and nothing has to be split.
--
-- Written only by the read-model projectors (shared.readmodel), from Identity's role events and Academic's events; read through
-- shared.readmodel.ReadModelRoleGrantSource, ReadModelAcademicDirectory and ReadModelGuardianDelegationChecker. Every projected row carries the
-- time of the event that last wrote it and an older event never overwrites a newer one, so a replay or an out-of-order delivery converges.
--
-- The initial content is copied below from the owning schemas. That works only while there is one database; afterwards it becomes a snapshot
-- export plus the replay of the topics. Same reasoning as V80 (institution.rm_*), which is Academic's own copy of Identity's data.

DO $migration$
DECLARE
    s text;
BEGIN
    FOREACH s IN ARRAY ARRAY['learning', 'assessment', 'attendance'] LOOP
        -- Identity's data: who holds which role, and what each role may do
        EXECUTE format('CREATE TABLE %I.rm_role_grants (
            user_role_id UUID PRIMARY KEY, user_id UUID NOT NULL, tenant_id UUID NULL, role_code TEXT NOT NULL,
            starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NULL)', s);
        EXECUTE format('CREATE INDEX idx_%s_rm_role_grants_user ON %I.rm_role_grants (user_id)', s, s);
        EXECUTE format('CREATE TABLE %I.rm_role_permissions (
            role_code TEXT NOT NULL, permission_code TEXT NOT NULL, PRIMARY KEY (role_code, permission_code))', s);

        -- Academic's data
        EXECUTE format('CREATE TABLE %I.rm_students (
            student_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, owner_user_id UUID NULL, updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE INDEX idx_%s_rm_students_owner ON %I.rm_students (tenant_id, owner_user_id)', s, s);
        EXECUTE format('CREATE TABLE %I.rm_faculty (
            faculty_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, owner_user_id UUID NULL, status TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE INDEX idx_%s_rm_faculty_owner ON %I.rm_faculty (tenant_id, owner_user_id)', s, s);
        EXECUTE format('CREATE TABLE %I.rm_faculty_assignments (
            assignment_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, section_id UUID NOT NULL, faculty_id UUID NOT NULL,
            ends_at DATE NULL, updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE INDEX idx_%s_rm_faculty_assignments_section ON %I.rm_faculty_assignments (section_id, faculty_id)', s, s);
        EXECUTE format('CREATE TABLE %I.rm_enrollments (
            enrollment_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, section_id UUID NOT NULL, student_id UUID NOT NULL,
            active BOOLEAN NOT NULL, updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE INDEX idx_%s_rm_enrollments_section ON %I.rm_enrollments (section_id, student_id)', s, s);
        EXECUTE format('CREATE TABLE %I.rm_colleges (
            tenant_id UUID PRIMARY KEY, name TEXT NOT NULL, timezone TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE TABLE %I.rm_sections (
            section_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE TABLE %I.rm_meetings (
            meeting_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, section_id UUID NOT NULL, removed BOOLEAN NOT NULL DEFAULT FALSE,
            updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE TABLE %I.rm_guardian_grants (
            grant_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, student_id UUID NOT NULL, guardian_user_id UUID NULL,
            expires_at TIMESTAMPTZ NULL, revoked_at TIMESTAMPTZ NULL, updated_at TIMESTAMPTZ NOT NULL)', s);
        EXECUTE format('CREATE INDEX idx_%s_rm_guardian_grants_guardian ON %I.rm_guardian_grants (guardian_user_id, student_id)', s, s);
        EXECUTE format('CREATE TABLE %I.rm_guardian_grant_permissions (
            grant_id UUID NOT NULL, permission_code TEXT NOT NULL, PRIMARY KEY (grant_id, permission_code))', s);

        -- Events already applied, by outbox event id, in the same transaction as the change (Kafka delivers at least once)
        EXECUTE format('CREATE TABLE %I.rm_processed_events (
            event_id UUID PRIMARY KEY, event_type TEXT NOT NULL, processed_at TIMESTAMPTZ NOT NULL DEFAULT now())', s);

        -- Initial content, from the owning schemas
        EXECUTE format('INSERT INTO %I.rm_role_grants (user_role_id, user_id, tenant_id, role_code, starts_at, ends_at)
            SELECT ur.id, ur.user_id, ur.tenant_id, r.code, ur.starts_at, ur.ends_at
            FROM identity.user_roles ur JOIN identity.roles r ON r.id = ur.role_id', s);
        EXECUTE format('INSERT INTO %I.rm_role_permissions (role_code, permission_code)
            SELECT r.code, rp.permission_code FROM identity.role_permissions rp JOIN identity.roles r ON r.id = rp.role_id', s);
        EXECUTE format('INSERT INTO %I.rm_students (student_id, tenant_id, owner_user_id, updated_at)
            SELECT sp.id, sp.tenant_id, p.user_id, to_timestamp(0)
            FROM directory.student_profiles sp JOIN identity.persons p ON p.id = sp.person_id', s);
        EXECUTE format('INSERT INTO %I.rm_faculty (faculty_id, tenant_id, owner_user_id, status, updated_at)
            SELECT fp.id, fp.tenant_id, p.user_id, fp.status, to_timestamp(0)
            FROM directory.faculty_profiles fp JOIN identity.persons p ON p.id = fp.person_id', s);
        EXECUTE format('INSERT INTO %I.rm_faculty_assignments (assignment_id, tenant_id, section_id, faculty_id, ends_at, updated_at)
            SELECT id, tenant_id, section_id, faculty_id, ends_at, to_timestamp(0) FROM enrollment.faculty_assignments', s);
        EXECUTE format('INSERT INTO %I.rm_enrollments (enrollment_id, tenant_id, section_id, student_id, active, updated_at)
            SELECT id, tenant_id, section_id, student_id, status = ''ENROLLED'', to_timestamp(0)
            FROM enrollment.enrollments WHERE status IN (''ENROLLED'', ''DROPPED'', ''COMPLETED'')', s);
        EXECUTE format('INSERT INTO %I.rm_colleges (tenant_id, name, timezone, updated_at)
            SELECT id, name, timezone, to_timestamp(0) FROM institution.colleges', s);
        EXECUTE format('INSERT INTO %I.rm_sections (section_id, tenant_id, updated_at)
            SELECT id, tenant_id, to_timestamp(0) FROM enrollment.course_sections', s);
        EXECUTE format('INSERT INTO %I.rm_meetings (meeting_id, tenant_id, section_id, updated_at)
            SELECT id, tenant_id, section_id, to_timestamp(0) FROM catalog.meeting_patterns', s);
        EXECUTE format('INSERT INTO %I.rm_guardian_grants (grant_id, tenant_id, student_id, guardian_user_id, expires_at, revoked_at, updated_at)
            SELECT g.id, g.tenant_id, g.student_id, p.user_id, g.expires_at,
                   CASE WHEN g.status = ''REVOKED'' THEN COALESCE(g.revoked_at, now()) END, to_timestamp(0)
            FROM guardian_access.guardian_access_grants g
            JOIN directory.guardians gd ON gd.id = g.guardian_id JOIN identity.persons p ON p.id = gd.person_id', s);
        EXECUTE format('INSERT INTO %I.rm_guardian_grant_permissions (grant_id, permission_code)
            SELECT grant_id, permission_code FROM guardian_access.guardian_access_permissions', s);

        EXECUTE format('COMMENT ON TABLE %I.rm_role_grants IS %L', s, 'Read model of identity.user_roles, fed by UserRoleAssigned/UserRoleRevoked. Read by the authorization engine.');
        EXECUTE format('COMMENT ON TABLE %I.rm_students IS %L', s, 'Read model of directory.student_profiles (id, college, owning account), fed by StudentAdmitted.');
        EXECUTE format('COMMENT ON TABLE %I.rm_enrollments IS %L', s, 'Read model of enrollment.enrollments: which students are in which sections, fed by StudentEnrolled/EnrollmentDropped.');
        EXECUTE format('COMMENT ON TABLE %I.rm_guardian_grants IS %L', s, 'Read model of guardian_access grants, fed by GuardianAccessGranted/GuardianAccessRevoked.');
    END LOOP;
END
$migration$;
