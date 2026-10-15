package com.setu.academic.readmodel;

import com.setu.shared.identity.PersonDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PersonDirectory} for the Academic service: reads from its copy of Identity's people ({@code institution.rm_persons}). People are
 * Identity-owned, so this directory is read-only: creating or changing a person is a call to Identity, and the copy follows from the
 * event it publishes. The two write methods refuse rather than quietly write to a table nothing else reads.
 */
public class ReadModelPersonDirectory implements PersonDirectory {

    private final JdbcClient jdbc;

    public ReadModelPersonDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID createPerson(UUID userId, String legalName, String preferredName, LocalDate dob, String gender, String email,
                             String phone, String addressJson) {
        throw new UnsupportedOperationException("people are owned by the Identity service: create the person there; this service learns of it from PersonRegistered");
    }

    @Override
    public boolean update(UUID personId, String legalName, String preferredName, String email, String phone) {
        throw new UnsupportedOperationException("people are owned by the Identity service: update the person there; this service learns of it from PersonUpdated");
    }

    @Override
    public Optional<PersonSummary> findById(UUID personId) {
        return jdbc.sql("SELECT person_id, user_id, legal_name, preferred_name, email, phone FROM institution.rm_persons WHERE person_id = :id")
            .param("id", personId).query(ReadModelPersonDirectory::map).optional();
    }

    @Override
    public Optional<PersonSummary> findByUserId(UUID userId) {
        return jdbc.sql("SELECT person_id, user_id, legal_name, preferred_name, email, phone FROM institution.rm_persons WHERE user_id = :id")
            .param("id", userId).query(ReadModelPersonDirectory::map).optional();
    }

    private static PersonSummary map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PersonSummary(rs.getObject("person_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("legal_name"),
            rs.getString("preferred_name"), rs.getString("email"), rs.getString("phone"));
    }
}
