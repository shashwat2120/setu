package com.setu.attendance.infrastructure.websocket;

import com.setu.directory.application.FacultyProfileService;
import com.setu.enrollment.application.FacultyAssignmentService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.websocket.StompDestinationAuthorizer;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 9 §8 destination authorization for the instructor live board:
 * {@code /topic/attendance/section/{sectionId}}. The destination string is
 * never trusted by itself — the section id is parsed out and re-validated
 * against real assignment data, the same assigned-faculty-or-admin check
 * {@link com.setu.attendance.application.AttendanceSessionService} uses for
 * open/close. A student is never an assigned faculty for any section, so
 * this naturally denies students without any role check.
 * <p>
 * Uses {@link AuthorizationContext#canAsUser} rather than {@code can}/
 * {@code assertCan}: STOMP message processing happens on the message
 * broker's own threads, which never populate a request-scoped
 * {@code SecurityContextHolder} the way an HTTP request does — the channel
 * interceptor only resolves a bare {@code userId} (see
 * {@code StompAuthorizationChannelInterceptor}), and {@code canAsUser} is
 * exactly the Day 9 addition to {@code AuthorizationContext} for checking a
 * permission against an explicit, already-resolved user id instead of "the
 * current thread's principal."
 */
@Component
public class AttendanceSubscriptionAuthorizer implements StompDestinationAuthorizer {

    private static final Pattern SECTION_DESTINATION = Pattern.compile("^/topic/attendance/section/([0-9a-fA-F-]{36})$");
    /** See {@code AttendanceSessionService.MANAGE_PERMISSION}'s javadoc for why this is NOT the FACULTY-held {@code MANAGE_ATTENDANCE}. */
    private static final String MANAGE_PERMISSION = "MANAGE_ATTENDANCE_ALL_SECTIONS";

    private final CourseSectionDirectory courseSectionDirectory;
    private final FacultyProfileService facultyProfileService;
    private final FacultyAssignmentService facultyAssignmentService;
    private final AuthorizationContext authorizationContext;

    public AttendanceSubscriptionAuthorizer(CourseSectionDirectory courseSectionDirectory,
                                             FacultyProfileService facultyProfileService,
                                             FacultyAssignmentService facultyAssignmentService,
                                             AuthorizationContext authorizationContext) {
        this.courseSectionDirectory = courseSectionDirectory;
        this.facultyProfileService = facultyProfileService;
        this.facultyAssignmentService = facultyAssignmentService;
        this.authorizationContext = authorizationContext;
    }

    @Override
    public boolean supports(String destination) {
        return SECTION_DESTINATION.matcher(destination).matches();
    }

    @Override
    public boolean authorize(UUID userId, String destination) {
        Matcher matcher = SECTION_DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            return false;
        }
        UUID sectionId = UUID.fromString(matcher.group(1));
        Optional<UUID> tenantId = courseSectionDirectory.findTenantId(sectionId);
        if (tenantId.isEmpty()) {
            return false;
        }

        Optional<UUID> facultyId = facultyProfileService.findIdByTenantAndOwnerUserId(tenantId.get(), userId);
        if (facultyId.isPresent() && facultyAssignmentService.isActivelyAssigned(sectionId, facultyId.get())) {
            return true;
        }
        return authorizationContext.canAsUser(userId, MANAGE_PERMISSION, ResourceRef.of("attendance_session", sectionId, tenantId.get()))
            .isAllowed();
    }
}
