package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps identity.roles (V8) — a fixed, seeded catalog (V9), never created/edited by application code in Day 4's scope. */
@Entity
@Table(name = "roles", schema = "identity")
public class Role {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoleCode code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    private RoleScope scopeType;

    protected Role() {
    }

    public UUID id() {
        return id;
    }

    public RoleCode code() {
        return code;
    }

    public RoleScope scopeType() {
        return scopeType;
    }
}
