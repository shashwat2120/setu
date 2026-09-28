package com.setu.identity.infrastructure;

import com.setu.identity.domain.Person;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<Person, UUID> {

    Optional<Person> findByUserId(UUID userId);
}
