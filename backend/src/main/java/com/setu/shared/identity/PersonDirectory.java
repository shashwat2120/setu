package com.setu.shared.identity;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Cross-module person lookups/creation — see package-info for why this is a shared contract rather than a direct dependency on identity.application.PersonService. */
public interface PersonDirectory {

    UUID createPerson(UUID userId, String legalName, String preferredName, LocalDate dob, String gender,
                       String email, String phone, String addressJson);

    Optional<PersonSummary> findById(UUID personId);

    Optional<PersonSummary> findByUserId(UUID userId);

    boolean update(UUID personId, String legalName, String preferredName, String email, String phone);

    record PersonSummary(UUID id, UUID userId, String legalName, String preferredName, String email, String phone) {
    }
}
