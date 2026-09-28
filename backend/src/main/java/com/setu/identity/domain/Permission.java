package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps identity.permissions (V8) — a fixed, seeded catalog (V9, V54). */
@Entity
@Table(name = "permissions", schema = "identity")
public class Permission {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String resource;

    @Column(nullable = false)
    private String action;

    protected Permission() {
    }

    public String code() {
        return code;
    }
}
