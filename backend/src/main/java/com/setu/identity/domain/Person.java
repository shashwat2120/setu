package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Maps identity.persons (V13) — the canonical human identity that a login
 * account ({@code userId}, nullable — not every person is a user) and
 * role-specific profiles (student/faculty/guardian, directory-owned) both
 * point back to. Deliberately carries no student/faculty/guardian-specific
 * fields (Day 6 instruction §2) — those live on the respective profile
 * tables, keyed by {@code person_id}.
 */
@Entity
@Table(name = "persons", schema = "identity")
public class Person {

    // Same normalized-email policy as identity.users (see Argon2/registration
    // flow) — not re-deriving a new one; a plain non-empty local@domain shape,
    // full deliverability is out of scope for either.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[+]?[0-9()\\-\\s]{7,20}$");

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "preferred_name")
    private String preferredName;

    private LocalDate dob;

    @SuppressWarnings("unused") // mapped column: written by JPA, not read in Java yet
    private String gender;

    @Column(columnDefinition = "citext")
    private String email;

    private String phone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address_json", columnDefinition = "jsonb")
    private String addressJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected Person() {
    }

    public Person(UUID id, UUID userId, String legalName, String preferredName, LocalDate dob, String gender,
                  String email, String phone, String addressJson, Instant now) {
        validateEmail(email);
        validatePhone(phone);
        this.id = id;
        this.userId = userId;
        this.legalName = legalName;
        this.preferredName = preferredName;
        this.dob = dob;
        this.gender = gender;
        this.email = email;
        this.phone = phone;
        this.addressJson = addressJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Minimal constructor for the common case (no contact details yet). */
    public static Person withLegalName(UUID id, UUID userId, String legalName, Instant now) {
        return new Person(id, userId, legalName, null, null, null, null, null, null, now);
    }

    private static void validateEmail(String email) {
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }
    }

    private static void validatePhone(String phone) {
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new IllegalArgumentException("Invalid phone format: " + phone);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String legalName() {
        return legalName;
    }

    public String preferredName() {
        return preferredName;
    }

    public LocalDate dob() {
        return dob;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public void update(String legalName, String preferredName, String email, String phone, Instant now) {
        validateEmail(email);
        validatePhone(phone);
        this.legalName = legalName;
        this.preferredName = preferredName;
        this.email = email;
        this.phone = phone;
        this.updatedAt = now;
    }
}
