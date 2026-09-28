package com.setu.shared.admin;

import com.setu.directory.application.FacultyProfileService;
import com.setu.identity.application.PasswordResetService;
import com.setu.identity.application.RoleAssignmentService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Manual faculty onboarding. The passkey step-up and all permissions are checked before any account rows are created. */
@Service
public class FacultyOnboardingService {
    private final UserRegistrationService registrations;
    private final PersonDirectory people;
    private final FacultyProfileService facultyProfiles;
    private final RoleAssignmentService roles;
    private final PasswordResetService passwordResets;
    private final AuthorizationContext authorization;

    public FacultyOnboardingService(UserRegistrationService registrations, PersonDirectory people,
                                    FacultyProfileService facultyProfiles, RoleAssignmentService roles,
                                    PasswordResetService passwordResets, AuthorizationContext authorization) {
        this.registrations = registrations; this.people = people; this.facultyProfiles = facultyProfiles;
        this.roles = roles; this.passwordResets = passwordResets; this.authorization = authorization;
    }

    public record Created(UUID userId, UUID personId, UUID facultyId) {}

    public Optional<Created> onboard(UUID tenantId, String fullName, LocalDate dob, String email, String phone,
                                     String employeeNo, String designation, LocalDate joinedAt) {
        authorization.assertStepUp();
        var resource = ResourceRef.of("faculty_profile", UUID.randomUUID(), tenantId);
        authorization.assertCan("MANAGE_FACULTY", resource);
        authorization.assertCan("MANAGE_TENANT_ROLES", ResourceRef.of("user_role", UUID.randomUUID(), tenantId));
        Optional<UUID> userId = registrations.registerIfNew(email);
        if (userId.isEmpty()) return Optional.empty();
        UUID personId = people.createPerson(userId.get(), fullName, null, dob, null, email, phone, null);
        Optional<UUID> facultyId = facultyProfiles.createIfPossible(tenantId, personId, employeeNo, designation, joinedAt);
        if (facultyId.isEmpty()) throw new IllegalStateException("faculty profile could not be created after validating new account");
        roles.assignRole(userId.get(), RoleCode.FACULTY, tenantId);
        passwordResets.initiateAndEmail(email);
        return Optional.of(new Created(userId.get(), personId, facultyId.get()));
    }
}
