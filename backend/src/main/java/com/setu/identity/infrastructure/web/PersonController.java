package com.setu.identity.infrastructure.web;

import com.setu.identity.application.PersonService;
import com.setu.identity.domain.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/**
 * Day 6 §2/§12: persons are the canonical human identity, PII-sensitive.
 * There is deliberately no standalone "create a bare person" or "list
 * persons" endpoint — a person is always created as part of a larger,
 * already-authorized admission/onboarding flow (see
 * directory.StudentProfileService#admitStudent and its siblings), and a
 * platform-wide person directory would itself be a PII leak surface no
 * Day 6 requirement asks for. This controller covers only self-service
 * read/update — admins see a person's PII through the tenant-scoped
 * profile endpoints (student/faculty/guardian), not here.
 */
@RestController
@RequestMapping("/identity/persons")
public class PersonController {

    private final PersonService personService;

    public PersonController(PersonService personService) {
        this.personService = personService;
    }

    public record PersonResponse(UUID id, String legalName, String preferredName, String email, String phone) {
    }

    @GetMapping("/{personId}")
    public ResponseEntity<?> get(@PathVariable UUID personId, Principal principal) {
        UUID currentUserId = requireUserId(principal);
        var person = personService.findById(personId);
        if (person.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!currentUserId.equals(person.get().userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var p = person.get();
        return ResponseEntity.ok(new PersonResponse(p.id(), p.legalName(), p.preferredName(), p.email(), p.phone()));
    }

    public record UpdateRequest(String legalName, String preferredName, String email, String phone) {
    }

    @PutMapping("/{personId}")
    public ResponseEntity<?> update(@PathVariable UUID personId, @RequestBody UpdateRequest request, Principal principal) {
        UUID currentUserId = requireUserId(principal);
        var person = personService.findById(personId);
        if (person.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!currentUserId.equals(person.get().userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        boolean updated = personService.update(personId, request.legalName(), request.preferredName(),
            request.email(), request.phone());
        return updated ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private static UUID requireUserId(Principal principal) {
        if (principal instanceof org.springframework.security.authentication.UsernamePasswordAuthenticationToken token
            && token.getPrincipal() instanceof UserPrincipal userPrincipal) {
            return userPrincipal.userId();
        }
        throw new AccessDeniedException("not authenticated");
    }
}
