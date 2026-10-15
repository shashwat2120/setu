package com.setu.identity.application;

import com.setu.identity.domain.Person;
import com.setu.identity.domain.PersonRegisteredEvent;
import com.setu.identity.domain.PersonUpdatedEvent;
import com.setu.identity.infrastructure.PersonRepository;
import com.setu.shared.governance.OutboxEventWriter;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity's implementation of the shared {@link PersonDirectory} port
 * (Day 6 §2/§13). Directory-owned profiles (student/faculty/guardian)
 * reference {@code person_id} but never touch {@code identity.persons}
 * directly — they go through {@code PersonDirectory}.
 * <p>
 * NOT {@code @NamedInterface}: cross-module callers depend on
 * {@code com.setu.shared.identity.PersonDirectory}, never on this concrete
 * class — see that interface's package-info for why (breaking a genuine
 * directory&nbsp;&#8596;&nbsp;identity dependency cycle
 * {@code ApplicationModules.verify()} caught). Spring wires the interface
 * to this bean at runtime; Modulith only looks at compile-time type
 * references, so this class never needs to be exposed itself.
 * <p>
 * Deliberately no authorization gate of its own: creating a person is
 * always a step inside a larger, already-authorized operation (student
 * admission, faculty onboarding, guardian registration) — the CALLING
 * service has already checked MANAGE_STUDENTS/etc. before reaching here.
 */
@Service
public class PersonService implements PersonDirectory {

    private final PersonRepository personRepository;
    private final OutboxEventWriter outboxEventWriter;

    public PersonService(PersonRepository personRepository, OutboxEventWriter outboxEventWriter) {
        this.personRepository = personRepository;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    @Transactional
    public UUID createPerson(UUID userId, String legalName, String preferredName, LocalDate dob, String gender,
                              String email, String phone, String addressJson) {
        Person person = new Person(UUID.randomUUID(), userId, legalName, preferredName, dob, gender, email, phone,
            addressJson, Instant.now());
        personRepository.save(person);
        var event = new PersonRegisteredEvent(UUID.randomUUID(), Instant.now(), person.id(), UUID.randomUUID(), null,
            person.id(), userId, legalName, preferredName, email, phone);
        outboxEventWriter.write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(), event,
            event.tenantId(), event.correlationId(), event.causationId());
        return person.id();
    }

    @Override
    public Optional<PersonSummary> findById(UUID personId) {
        return personRepository.findById(personId).map(PersonService::toSummary);
    }

    @Override
    public Optional<PersonSummary> findByUserId(UUID userId) {
        return personRepository.findByUserId(userId).map(PersonService::toSummary);
    }

    @Override
    @Transactional
    public boolean update(UUID personId, String legalName, String preferredName, String email, String phone) {
        Optional<Person> maybePerson = personRepository.findById(personId);
        if (maybePerson.isEmpty()) {
            return false;
        }
        Person person = maybePerson.get();
        person.update(legalName, preferredName, email, phone, Instant.now());
        personRepository.save(person);
        var event = new PersonUpdatedEvent(UUID.randomUUID(), Instant.now(), person.id(), UUID.randomUUID(), null,
            person.id(), person.userId(), legalName, preferredName, email, phone);
        outboxEventWriter.write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(), event,
            event.tenantId(), event.correlationId(), event.causationId());
        return true;
    }

    private static PersonSummary toSummary(Person p) {
        return new PersonSummary(p.id(), p.userId(), p.legalName(), p.preferredName(), p.email(), p.phone());
    }
}
