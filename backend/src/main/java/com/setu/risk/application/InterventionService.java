package com.setu.risk.application;

import com.setu.directory.application.StudentProfileService;
import com.setu.risk.domain.Intervention;
import com.setu.risk.domain.InterventionAction;
import com.setu.risk.domain.InterventionCompletedEvent;
import com.setu.risk.domain.InterventionCreatedEvent;
import com.setu.risk.domain.InterventionStatus;
import com.setu.risk.domain.RiskScore;
import com.setu.risk.infrastructure.InterventionActionRepository;
import com.setu.risk.infrastructure.InterventionRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 13 §Student Success Engine: interventions/intervention_actions —
 * state-machines.md #23 exactly. Creation only happens via {@link
 * #createFromRecommendation}, called from {@code
 * RecommendationService#accept} in the SAME transaction — an intervention
 * is never created standalone, matching the ERD's "ACCEPTED... creates a
 * linked interventions row" rule. Status transitions are owner-scoped
 * (the assigned {@code ownerUserId}) or COLLEGE_ADMIN-wide; COMPLETED
 * requires at least one recorded {@link InterventionAction} first — a
 * rule the entity itself cannot enforce since it needs a repository
 * count, so it lives here.
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class InterventionService {

    private static final String MANAGE_PERMISSION = "MANAGE_INTERVENTIONS";
    private static final String MANAGE_PERMISSION_ALL = "MANAGE_INTERVENTIONS_ALL_SECTIONS";
    private static final String VIEW_PERMISSION = "VIEW_RISK_SCORE";
    private static final String VIEW_PERMISSION_ALL = "VIEW_RISK_SCORE_ALL_SECTIONS";

    private final InterventionRepository interventionRepository;
    private final InterventionActionRepository interventionActionRepository;
    private final RiskAccessSupport accessSupport;
    private final StudentProfileService studentProfileService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public InterventionService(InterventionRepository interventionRepository, InterventionActionRepository interventionActionRepository,
                                RiskAccessSupport accessSupport, StudentProfileService studentProfileService,
                                AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                OutboxEventWriter outboxEventWriter) {
        this.interventionRepository = interventionRepository;
        this.interventionActionRepository = interventionActionRepository;
        this.accessSupport = accessSupport;
        this.studentProfileService = studentProfileService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    /** Called only from {@code RecommendationService#accept}, already authorized by that caller — never an independent entry point. */
    UUID createFromRecommendation(RiskScore riskScore, UUID recommendationId, UUID ownerUserId, LocalDate dueDate) {
        Intervention intervention = new Intervention(UUID.randomUUID(), riskScore.tenantId(), riskScore.studentId(),
            riskScore.id(), recommendationId, ownerUserId, dueDate);
        interventionRepository.save(intervention);
        UUID correlationId = UUID.randomUUID();
        var event = new InterventionCreatedEvent(UUID.randomUUID(), Instant.now(), riskScore.tenantId(), riskScore.studentId(),
            correlationId, null, intervention.id(), riskScore.studentId(), ownerUserId);
        outboxEventWriter.write("interventions", riskScore.studentId(), event.eventType(), event.eventVersion(), event,
            riskScore.tenantId(), correlationId, null);
        return intervention.id();
    }

    public enum TransitionResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION, COMPLETION_REQUIRES_ACTION, REOPEN_REQUIRES_ADMIN}

    @Transactional
    public TransitionResult transition(UUID interventionId, InterventionStatus target) {
        Optional<Intervention> maybe = interventionRepository.findById(interventionId);
        if (maybe.isEmpty()) {
            return TransitionResult.NOT_FOUND;
        }
        Intervention intervention = maybe.get();
        assertOwnerOrAdmin(intervention);
        if (!intervention.canTransitionTo(target)) {
            return TransitionResult.INVALID_TRANSITION;
        }
        if (intervention.status() == InterventionStatus.COMPLETED && target == InterventionStatus.IN_PROGRESS
            && !authorizationContext.can(MANAGE_PERMISSION_ALL,
                ResourceRef.of("intervention", intervention.studentId(), intervention.tenantId())).isAllowed()) {
            return TransitionResult.REOPEN_REQUIRES_ADMIN;
        }
        if (target == InterventionStatus.COMPLETED
            && interventionActionRepository.countByInterventionId(interventionId) == 0) {
            return TransitionResult.COMPLETION_REQUIRES_ACTION;
        }
        int updated = interventionRepository.transitionIfStatus(interventionId, intervention.status(), target);
        if (updated == 0) {
            return TransitionResult.INVALID_TRANSITION;
        }
        auditLogWriter.write(intervention.tenantId(), authorizationContext.currentUserId(), "INTERVENTION_" + target,
            "risk.interventions", interventionId, Map.of("studentId", intervention.studentId()));
        if (target == InterventionStatus.COMPLETED) {
            String outcome = interventionActionRepository.findByInterventionIdOrderByCreatedAtDesc(interventionId)
                .stream().findFirst().map(InterventionAction::action).orElse("");
            UUID correlationId = UUID.randomUUID();
            var event = new InterventionCompletedEvent(UUID.randomUUID(), Instant.now(), intervention.tenantId(),
                intervention.studentId(), correlationId, null, interventionId, outcome);
            outboxEventWriter.write("interventions", intervention.studentId(), event.eventType(), event.eventVersion(),
                event, intervention.tenantId(), correlationId, null);
        }
        return TransitionResult.SUCCESS;
    }

    public enum AddActionResult {SUCCESS, NOT_FOUND, TERMINAL_STATE}

    @Transactional
    public AddActionResult addAction(UUID interventionId, String action, String notes) {
        Optional<Intervention> maybe = interventionRepository.findById(interventionId);
        if (maybe.isEmpty()) {
            return AddActionResult.NOT_FOUND;
        }
        Intervention intervention = maybe.get();
        assertOwnerOrAdmin(intervention);
        if (intervention.status() == InterventionStatus.CANCELLED) {
            return AddActionResult.TERMINAL_STATE;
        }
        InterventionAction record = new InterventionAction(UUID.randomUUID(), interventionId, action,
            authorizationContext.currentUserId(), notes, Instant.now());
        interventionActionRepository.save(record);
        auditLogWriter.write(intervention.tenantId(), authorizationContext.currentUserId(), "INTERVENTION_ACTION_RECORDED",
            "risk.interventions", interventionId, Map.of("action", action));
        return AddActionResult.SUCCESS;
    }

    /** Tenant is always resolved from the student's own profile, never accepted from the caller. */
    @Transactional(readOnly = true)
    public List<Intervention> listForStudent(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return List.of();
        }
        assertView(studentInfo.get().tenantId(), studentId);
        return interventionRepository.findByStudentId(studentId);
    }

    /** No independent authorization — callers already resolved and authorized the owning {@link Intervention} via {@link #getById}/{@link #listForStudent}. */
    @Transactional(readOnly = true)
    public List<InterventionAction> actionsFor(UUID interventionId) {
        return interventionActionRepository.findByInterventionIdOrderByCreatedAtDesc(interventionId);
    }

    /** Fetch one intervention by its own id, authorized against the row's OWN tenant/student — same IDOR-safe shape as {@code RiskCalculationService#getById}. */
    @Transactional(readOnly = true)
    public Optional<Intervention> getById(UUID interventionId) {
        Optional<Intervention> maybe = interventionRepository.findById(interventionId);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        Intervention intervention = maybe.get();
        assertView(intervention.tenantId(), intervention.studentId());
        return maybe;
    }

    /**
     * Day 16 §student-success: real gap this session's risk-data verification found — an owner who accepted their
     * own recommendation while authenticated as COLLEGE_ADMIN (rather than the advising FACULTY) holds only the
     * tenant-wide {@code MANAGE_INTERVENTIONS_ALL_SECTIONS}, never the narrow owner-scoped {@code
     * MANAGE_INTERVENTIONS} a plain advisor holds. The class javadoc's own stated rule — "owner-scoped... or
     * COLLEGE_ADMIN-wide" — is an OR, so the owner branch must fall back to the tenant-wide check rather than
     * failing outright, matching the same narrow-then-broad idiom already used by {@code RiskCalculationService
     * #assertManage} and {@code RecommendationService}'s own equivalent.
     */
    private void assertOwnerOrAdmin(Intervention intervention) {
        UUID currentUserId = authorizationContext.currentUserId();
        if (currentUserId.equals(intervention.ownerUserId())
            && authorizationContext.can(MANAGE_PERMISSION,
                ResourceRef.of("intervention", intervention.studentId(), intervention.tenantId()).ownedBy(currentUserId)).isAllowed()) {
            return;
        }
        authorizationContext.assertCan(MANAGE_PERMISSION_ALL,
            ResourceRef.of("intervention", intervention.studentId(), intervention.tenantId()));
    }

    private void assertView(UUID tenantId, UUID studentId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION,
                ResourceRef.of("intervention", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (accessSupport.currentUserIsAdvisorOf(tenantId, studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION,
                ResourceRef.of("intervention", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        authorizationContext.assertCan(VIEW_PERMISSION_ALL, ResourceRef.of("intervention", studentId, tenantId));
    }
}
