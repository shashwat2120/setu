package com.setu.shared.admin;

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

/**
 * The real, HTTP-reachable "College Admin admits a new student" workflow — the manual-UI counterpart to what
 * {@code shared.seed.DemoDataSeeder} does for seeded accounts, composing the exact same real, already-authorized
 * service calls ({@link UserRegistrationService#register}, {@link PersonDirectory#createPerson},
 * {@link StudentProfileService#admitStudent}, {@link RoleAssignmentService#assignRole},
 * {@link PasswordResetService#initiateAndEmail}) instead of the seeder's unguarded bootstrap paths.
 * <p>
 * Lives in {@code shared.admin} rather than inside {@code identity} or {@code directory} because it genuinely
 * spans both — the same reason {@code DemoDataSeeder} lives in {@code shared.seed} rather than either module it
 * composes. Depends only on each module's own {@code @NamedInterface}-exposed services/shared ports, never their
 * internal domain or infrastructure packages.
 * <p>
 * Authorization: this class's own {@code assertCan(MANAGE_STUDENTS, ...)} check runs FIRST, before any write —
 * {@link StudentProfileService#admitStudent} asserts the same permission again internally (defense in depth, the
 * same idiom every other multi-step service in this codebase already uses), but without this class's own
 * up-front check an unauthorized caller could still register a throwaway account and person row before being
 * rejected at the admission step. {@link RoleAssignmentService#assignRole} separately asserts
 * {@code MANAGE_TENANT_ROLES} for the STUDENT role grant — held by the same COLLEGE_ADMIN/SUPER_ADMIN roles that
 * hold MANAGE_STUDENTS (V55/V59), and, per {@link RoleAssignmentService}'s own scoped-step-up documentation,
 * granting STUDENT does not additionally require passkey step-up.
 */
@Service
public class StudentAdmissionService {

    private static final String MANAGE_PERMISSION = "MANAGE_STUDENTS";

    private final UserRegistrationService userRegistrationService;
    private final PersonDirectory personDirectory;
    private final StudentProfileService studentProfileService;
    private final RoleAssignmentService roleAssignmentService;
    private final PasswordResetService passwordResetService;
    private final AuthorizationContext authorizationContext;

    public StudentAdmissionService(UserRegistrationService userRegistrationService, PersonDirectory personDirectory,
                                    StudentProfileService studentProfileService, RoleAssignmentService roleAssignmentService,
                                    PasswordResetService passwordResetService, AuthorizationContext authorizationContext) {
        this.userRegistrationService = userRegistrationService;
        this.personDirectory = personDirectory;
        this.studentProfileService = studentProfileService;
        this.roleAssignmentService = roleAssignmentService;
        this.passwordResetService = passwordResetService;
        this.authorizationContext = authorizationContext;
    }

    public sealed interface AdmitNewStudentResult {
        record Success(UUID userId, UUID personId, UUID studentId) implements AdmitNewStudentResult {
        }

        record EmailAlreadyRegistered() implements AdmitNewStudentResult {
        }

        record DuplicateAdmissionNo() implements AdmitNewStudentResult {
        }
    }

    /**
     * Deliberately NOT wrapped in its own {@code @Transactional} — see this class's own javadoc for
     * {@link StudentProfileService#admitStudent}'s REQUIRES_NEW guard, and why a caller-level transaction here
     * would hide the just-created person row from it (a real bug found live via a test failure while building
     * this method, not a hypothetical). Each step below already commits on its own, the exact same shape
     * {@code shared.seed.DemoDataSeeder} already relies on for this identical reason. The accepted tradeoff,
     * same one already disclosed at the {@code DuplicateAdmissionNo} return below: if {@link
     * RoleAssignmentService#assignRole} or {@link PasswordResetService#initiateAndEmail} somehow failed after a
     * successful admission, the account/person/profile rows above it would stay committed without a role or
     * reset email — real but very low risk, since the SAME {@code MANAGE_STUDENTS}/{@code MANAGE_TENANT_ROLES}
     * permission check already passed earlier in this exact call.
     */
    public AdmitNewStudentResult admitNewStudent(UUID tenantId, String fullName, String preferredName, LocalDate dob,
                                                  String gender, String email, String phone, String admissionNo,
                                                  String rollNo, LocalDate admissionDate) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_profile", UUID.randomUUID(), tenantId));

        Optional<UUID> userId = userRegistrationService.registerIfNew(email);
        if (userId.isEmpty()) {
            return new AdmitNewStudentResult.EmailAlreadyRegistered();
        }

        UUID personId = personDirectory.createPerson(userId.get(), fullName, preferredName, dob, gender, email, phone, null);

        Optional<UUID> studentId = studentProfileService.admitStudentIfPossible(tenantId, personId, admissionNo, rollNo, admissionDate);
        if (studentId.isEmpty()) {
            // DuplicateAdmissionNo is the only realistic outcome here (PersonNotFound can't happen — personId was
            // just created above; PersonAlreadyHasProfileInTenant can't happen — userId, and therefore personId,
            // is brand new). Reported uniformly rather than distinguished further: the account/person rows
            // created above are real, minor cleanup debt on this rare path, not a security or data-integrity
            // concern (no student profile, no role, no access was ever granted).
            return new AdmitNewStudentResult.DuplicateAdmissionNo();
        }

        roleAssignmentService.assignRole(userId.get(), RoleCode.STUDENT, tenantId);
        passwordResetService.initiateAndEmail(email);

        return new AdmitNewStudentResult.Success(userId.get(), personId, studentId.get());
    }
}
