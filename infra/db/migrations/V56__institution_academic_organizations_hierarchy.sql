-- Sprint 5C Day 5 gap-closure, disclosed per the Day 5 report: the
-- canonical ERD (docs/erd/02-academic.md) and V7's original migration both
-- modeled academic_organizations as flat (one level directly under a
-- campus, key columns only name/type) — neither ever included a
-- self-referencing parent column. The Day 5 instruction explicitly requires
-- a HIERARCHICAL academic organization (College -> Faculty -> Department-
-- level sub-units) with cycle prevention, which the flat table cannot
-- represent at all. This is the same class of gap as V51 (password_hash)
-- and V53 (role_permissions): a column the already-locked requirements
-- imply but the migration never added — applied directly and disclosed,
-- not a redesign of the table's ownership, schema, or existing columns.
--
-- Nullable: a top-level organization (e.g. "Faculty of Engineering" itself)
-- has no parent. Cross-tenant and cycle validation are enforced at the
-- application layer (AcademicOrganizationService) — see that class's
-- javadoc for why a declarative CHECK constraint can't express "no cycles
-- across rows" without a trigger, and why a trigger wasn't used here.
ALTER TABLE institution.academic_organizations
    ADD COLUMN parent_organization_id UUID NULL REFERENCES institution.academic_organizations (id);
CREATE INDEX idx_academic_organizations_parent ON institution.academic_organizations (parent_organization_id);
