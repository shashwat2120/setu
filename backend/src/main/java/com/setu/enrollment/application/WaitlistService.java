package com.setu.enrollment.application;

import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.domain.WaitlistEntry;
import com.setu.enrollment.domain.WaitlistStatus;
import com.setu.enrollment.infrastructure.WaitlistEntryRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/self-withdraw operations on {@link WaitlistEntry} (Day 8 §5).
 * Position-changing operations (assignment on wait, promotion on drop,
 * re-compaction) live in {@link EnrollmentService} because they must run in
 * the SAME transaction as the enrollment request/drop that triggers them
 * (transaction-concurrency-matrix.md scenarios 1-2) — splitting that logic
 * into this class would risk a second, separately-committed transaction,
 * exactly what the concurrency matrix says NOT to do.
 */
@Service
public class WaitlistService {

    private static final String MANAGE_PERMISSION = "MANAGE_WAITLIST";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final StudentProfileService studentProfileService;
    private final AuthorizationContext authorizationContext;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository, StudentProfileService studentProfileService,
                            AuthorizationContext authorizationContext) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.studentProfileService = studentProfileService;
        this.authorizationContext = authorizationContext;
    }

    @Transactional(readOnly = true)
    public List<WaitlistEntry> listForSection(UUID sectionId, UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("waitlist_entry", UUID.randomUUID(), tenantId));
        return waitlistEntryRepository.findBySectionIdAndStatusOrderByPositionAsc(sectionId, WaitlistStatus.WAITING);
    }

    @Transactional(readOnly = true)
    public Optional<WaitlistEntry> getForStudent(UUID sectionId, UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return Optional.empty();
        }
        boolean isSelf = authorizationContext.currentUserId().equals(studentInfo.get().ownerUserId());
        if (!isSelf) {
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("waitlist_entry", UUID.randomUUID(), studentInfo.get().tenantId()));
        }
        return waitlistEntryRepository.findBySectionIdAndStudentId(sectionId, studentId);
    }

    public enum WithdrawResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    /** Self-service withdrawal from the waitlist — re-compaction of positions behind this entry happens here since no seat is freed and no enrollment promotion is triggered (unlike a drop). */
    @Transactional
    public WithdrawResult withdraw(UUID sectionId, UUID studentId) {
        Optional<WaitlistEntry> maybeEntry = waitlistEntryRepository.findBySectionIdAndStudentId(sectionId, studentId);
        if (maybeEntry.isEmpty()) {
            return WithdrawResult.NOT_FOUND;
        }
        WaitlistEntry entry = maybeEntry.get();
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        boolean isSelf = studentInfo.isPresent() && authorizationContext.currentUserId().equals(studentInfo.get().ownerUserId());
        if (!isSelf) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("waitlist_entry", entry.id(), entry.tenantId()));
        }
        try {
            entry.withdraw();
        } catch (IllegalStateException e) {
            return WithdrawResult.INVALID_TRANSITION;
        }
        waitlistEntryRepository.save(entry);
        List<WaitlistEntry> after = waitlistEntryRepository.findBySectionIdAndStatusAndPositionGreaterThan(
            sectionId, WaitlistStatus.WAITING, entry.position());
        for (WaitlistEntry e : after) {
            e.decrementPosition();
            waitlistEntryRepository.save(e);
        }
        return WithdrawResult.SUCCESS;
    }
}
