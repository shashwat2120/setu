package com.setu.shared.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Keeps a service's local copy of Identity's data current: who holds which role ({@code UserRoleAssigned}, {@code UserRoleRevoked}) and,
 * for the one service that shows people by name, who people are ({@code PersonRegistered}, {@code PersonUpdated}; only when {@code people}
 * is set, because only then does the schema have {@code rm_persons}).
 * <p>
 * <b>Idempotent</b> through the processed-event record. <b>Order-insensitive where it can be</b>: a revocation carries everything needed to
 * record itself, so it upserts, and an assignment never resurrects a grant a revocation already ended. Events about one assignment or person
 * share a Kafka key and arrive in order anyway; this only means a replay from the start of the topic rebuilds the same state.
 */
public class IdentityEventProjector extends BaseProjector {

    private final boolean people;

    public IdentityEventProjector(JdbcClient jdbc, ObjectMapper mapper, String schema, boolean people) {
        super(jdbc, mapper, schema);
        this.people = people;
    }

    @Override
    public boolean handles(String eventType) {
        return switch (eventType) {
            case "UserRoleAssigned", "UserRoleRevoked" -> true;
            case "PersonRegistered", "PersonUpdated" -> people;
            default -> false;
        };
    }

    @Override
    protected void project(String eventType, EventPayload p) {
        Instant occurredAt = p.instant("occurredAt");
        switch (eventType) {
            case "UserRoleAssigned" -> upsertGrant(p, occurredAt, null);
            case "UserRoleRevoked" -> upsertGrant(p, occurredAt, occurredAt);
            default -> upsertPerson(p);
        }
    }

    /** Assigned: insert, never overwriting a grant a revocation already ended. Revoked: insert if unseen, otherwise end it. */
    private void upsertGrant(EventPayload p, Instant occurredAt, Instant endsAt) {
        String conflict = endsAt == null ? "DO NOTHING" : "DO UPDATE SET ends_at = EXCLUDED.ends_at";
        jdbc.sql("INSERT INTO " + table("rm_role_grants") + " (user_role_id, user_id, tenant_id, role_code, starts_at, ends_at) "
                + "VALUES (:id, :user, :tenant, :role, :starts, :ends) ON CONFLICT (user_role_id) " + conflict)
            .param("id", p.uuid("userRoleId"))
            .param("user", p.uuid("userId"))
            .param("tenant", p.optionalUuid("tenantId"))
            .param("role", p.required("roleCode"))
            .param("starts", Timestamp.from(occurredAt))
            .param("ends", endsAt == null ? null : Timestamp.from(endsAt))
            .update();
    }

    private void upsertPerson(EventPayload p) {
        jdbc.sql("INSERT INTO " + table("rm_persons") + " (person_id, user_id, legal_name, preferred_name, email, phone) "
                + "VALUES (:id, :user, :legal, :preferred, :email, :phone) "
                + "ON CONFLICT (person_id) DO UPDATE SET user_id = EXCLUDED.user_id, legal_name = EXCLUDED.legal_name, "
                + "preferred_name = EXCLUDED.preferred_name, email = EXCLUDED.email, phone = EXCLUDED.phone")
            .param("id", p.uuid("personId"))
            .param("user", p.optionalUuid("userId"))
            .param("legal", p.required("legalName"))
            .param("preferred", p.text("preferredName"))
            .param("email", p.text("email"))
            .param("phone", p.text("phone"))
            .update();
    }
}
