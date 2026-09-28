package com.setu.shared.admin;

import com.setu.directory.application.GuardianProfileService;
import com.setu.directory.application.StudentGuardianService;
import com.setu.directory.application.StudentProfileService;
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

/** Creates a guardian account and relationship only; delegated access remains a separate explicit grant operation. */
@Service
public class GuardianOnboardingService {
    private final UserRegistrationService registrations;
    private final PersonDirectory people;
    private final GuardianProfileService guardians;
    private final StudentGuardianService relationships;
    private final StudentProfileService students;
    private final RoleAssignmentService roles;
    private final PasswordResetService passwordResets;
    private final AuthorizationContext authorization;

    public GuardianOnboardingService(UserRegistrationService registrations, PersonDirectory people,
                                     GuardianProfileService guardians, StudentGuardianService relationships,
                                     StudentProfileService students, RoleAssignmentService roles,
                                     PasswordResetService passwordResets, AuthorizationContext authorization) {
        this.registrations = registrations; this.people = people; this.guardians = guardians;
        this.relationships = relationships; this.students = students; this.roles = roles;
        this.passwordResets = passwordResets; this.authorization = authorization;
    }

    public sealed interface Result {
        record Created(UUID userId, UUID personId, UUID guardianId, UUID relationshipId) implements Result {}
        record StudentNotFound() implements Result {}
        record EmailAlreadyRegistered() implements Result {}
    }

    public Result createAndLink(UUID studentId, String fullName, LocalDate dob, String email, String phone,
                                String relationshipType, boolean primary, boolean emergencyContact) {
        Optional<UUID> tenant = students.findTenantId(studentId);
        if (tenant.isEmpty()) return new Result.StudentNotFound();
        UUID tenantId = tenant.get();

        // Both permission gates run before account/person/profile writes. The tenant comes from the persisted
        // student profile, never a tenant identifier supplied by the browser.
        authorization.assertCan("MANAGE_GUARDIANS", ResourceRef.of("student_guardian", studentId, tenantId));
        authorization.assertCan("MANAGE_TENANT_ROLES", ResourceRef.of("user_role", UUID.randomUUID(), tenantId));

        Optional<UUID> userId = registrations.registerIfNew(email);
        if (userId.isEmpty()) return new Result.EmailAlreadyRegistered();
        UUID personId = people.createPerson(userId.get(), fullName, null, dob, null, email, phone, null);
        Optional<UUID> guardianId = guardians.createIfPossible(tenantId, personId, null, null, null);
        if (guardianId.isEmpty()) throw new IllegalStateException("guardian profile could not be created for a new account");
        roles.assignRole(userId.get(), RoleCode.GUARDIAN, tenantId);
        Optional<UUID> relationshipId = relationships.linkIfPossible(studentId, guardianId.get(), relationshipType, primary, emergencyContact);
        if (relationshipId.isEmpty())
            throw new IllegalStateException("guardian relationship could not be created for a validated student and guardian");
        passwordResets.initiateAndEmail(email);
        return new Result.Created(userId.get(), personId, guardianId.get(), relationshipId.get());
    }
}
