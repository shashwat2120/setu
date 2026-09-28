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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class GuardianOnboardingServiceTests {
    private final UserRegistrationService registrations = mock(UserRegistrationService.class);
    private final PersonDirectory people = mock(PersonDirectory.class);
    private final GuardianProfileService guardians = mock(GuardianProfileService.class);
    private final StudentGuardianService relationships = mock(StudentGuardianService.class);
    private final StudentProfileService students = mock(StudentProfileService.class);
    private final RoleAssignmentService roles = mock(RoleAssignmentService.class);
    private final PasswordResetService passwordResets = mock(PasswordResetService.class);
    private final AuthorizationContext authorization = mock(AuthorizationContext.class);
    private GuardianOnboardingService service;

    @BeforeEach
    void setUp() { service = new GuardianOnboardingService(registrations, people, guardians, relationships, students, roles, passwordResets, authorization); }

    @Test
    void createsAccountProfileGuardianRoleAndRelationshipWithoutCreatingAnAccessGrant() {
        UUID tenant = UUID.randomUUID(), studentId = UUID.randomUUID(), userId = UUID.randomUUID();
        UUID personId = UUID.randomUUID(), guardianId = UUID.randomUUID(), relationshipId = UUID.randomUUID();
        String email = "family." + UUID.randomUUID() + "@example.test";
        when(students.findTenantId(studentId)).thenReturn(Optional.of(tenant));
        when(registrations.registerIfNew(email)).thenReturn(Optional.of(userId));
        when(people.createPerson(eq(userId), eq("Morgan Lee"), isNull(), any(), isNull(), eq(email), eq("+1-202-555-0142"), isNull())).thenReturn(personId);
        when(guardians.createIfPossible(tenant, personId, null, null, null)).thenReturn(Optional.of(guardianId));
        when(relationships.linkIfPossible(studentId, guardianId, "Parent", true, true))
            .thenReturn(Optional.of(relationshipId));

        var result = service.createAndLink(studentId, "Morgan Lee", LocalDate.of(1981, 5, 12), email,
            "+1-202-555-0142", "Parent", true, true);

        assertThat(result).isEqualTo(new GuardianOnboardingService.Result.Created(userId, personId, guardianId, relationshipId));
        verify(authorization).assertCan(eq("MANAGE_GUARDIANS"), any(ResourceRef.class));
        verify(authorization).assertCan(eq("MANAGE_TENANT_ROLES"), any(ResourceRef.class));
        verify(roles).assignRole(userId, RoleCode.GUARDIAN, tenant);
        verify(passwordResets).initiateAndEmail(email);
        verify(relationships).linkIfPossible(studentId, guardianId, "Parent", true, true);
        // This orchestration has no GuardianAccessService dependency: the relationship alone grants no student-data access.
        verifyNoMoreInteractions(passwordResets);
    }

    @Test
    void deniesAnAdminOutsideTheStudentTenantBeforeCreatingAnAccount() {
        UUID tenant = UUID.randomUUID(), studentId = UUID.randomUUID();
        when(students.findTenantId(studentId)).thenReturn(Optional.of(tenant));
        doThrow(new AccessDeniedException("denied")).when(authorization).assertCan(eq("MANAGE_GUARDIANS"), any(ResourceRef.class));

        assertThatThrownBy(() -> service.createAndLink(studentId, "Morgan Lee", null,
            "morgan." + UUID.randomUUID() + "@example.test", null, "Parent", false, false))
            .isInstanceOf(AccessDeniedException.class);
        verify(registrations, never()).registerIfNew(any());
        verifyNoInteractions(people, guardians, relationships, roles, passwordResets);
    }

    @Test
    void rejectsAnExistingEmailWithoutCreatingAnotherProfileOrLink() {
        UUID tenant = UUID.randomUUID(), studentId = UUID.randomUUID();
        String email = "existing." + UUID.randomUUID() + "@example.test";
        when(students.findTenantId(studentId)).thenReturn(Optional.of(tenant));
        when(registrations.registerIfNew(email)).thenReturn(Optional.empty());

        var result = service.createAndLink(studentId, "Morgan Lee", null, email, null, "Parent", false, false);

        assertThat(result).isInstanceOf(GuardianOnboardingService.Result.EmailAlreadyRegistered.class);
        verifyNoInteractions(people, guardians, relationships, roles, passwordResets);
    }

    @Test
    void reportsUnknownStudentWithoutReachingAuthorizationOrCreatingRecords() {
        UUID studentId = UUID.randomUUID();
        when(students.findTenantId(studentId)).thenReturn(Optional.empty());

        var result = service.createAndLink(studentId, "Morgan Lee", null,
            "morgan." + UUID.randomUUID() + "@example.test", null, "Parent", false, false);

        assertThat(result).isInstanceOf(GuardianOnboardingService.Result.StudentNotFound.class);
        verifyNoInteractions(authorization, registrations, people, guardians, relationships, roles, passwordResets);
    }
}
