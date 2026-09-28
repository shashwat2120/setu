package com.setu.risk.application;

import com.setu.risk.domain.GeneratedBy;
import com.setu.risk.domain.Recommendation;
import com.setu.risk.domain.RecommendationCreatedEvent;
import com.setu.risk.domain.RecommendationStatus;
import com.setu.risk.domain.RiskScore;
import com.setu.risk.infrastructure.RecommendationRepository;
import com.setu.risk.infrastructure.RiskScoreRepository;
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
 * Day 13 §Student Success Engine: recommendations — state-machines.md #22
 * exactly. {@link #proposeFromRule} is called only from {@link
 * RiskCalculationService#recalculate} (package-private, never a public
 * entry point of its own — a recommendation is always tied to the {@link
 * RiskScore} that motivated it). {@link #accept}/{@link #dismiss} are the
 * only human-triggered transitions; accepting always creates a linked
 * {@link com.setu.risk.domain.Intervention} in the SAME transaction (ERD's
 * own rule: "PROPOSED → ACCEPTED... creates a linked interventions row").
 * An AI-generated recommendation ({@code generatedBy == AI}, Day 14 scope,
 * not yet a real caller) can never self-accept — only a human {@link
 * #accept} call can, matching the canonical spec's AI-authority ban.
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class RecommendationService {

    private static final String MANAGE_PERMISSION = "MANAGE_INTERVENTIONS";
    private static final String MANAGE_PERMISSION_ALL = "MANAGE_INTERVENTIONS_ALL_SECTIONS";
    private static final String VIEW_PERMISSION = "VIEW_RISK_SCORE";
    private static final String VIEW_PERMISSION_ALL = "VIEW_RISK_SCORE_ALL_SECTIONS";

    private final RecommendationRepository recommendationRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final InterventionService interventionService;
    private final RiskAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public RecommendationService(RecommendationRepository recommendationRepository, RiskScoreRepository riskScoreRepository,
                                  InterventionService interventionService, RiskAccessSupport accessSupport,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                  OutboxEventWriter outboxEventWriter) {
        this.recommendationRepository = recommendationRepository;
        this.riskScoreRepository = riskScoreRepository;
        this.interventionService = interventionService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    /** Called only from {@code RiskCalculationService#recalculate}, within its own transaction — never independently authorized, since the caller has already authorized the recalculation itself. */
    Recommendation proposeFromRule(UUID riskScoreId, UUID tenantId, UUID studentId, String actionCode) {
        Recommendation recommendation = new Recommendation(UUID.randomUUID(), riskScoreId, actionCode, GeneratedBy.RULE);
        recommendationRepository.save(recommendation);
        UUID correlationId = UUID.randomUUID();
        var event = new RecommendationCreatedEvent(UUID.randomUUID(), Instant.now(), tenantId, studentId, correlationId,
            null, recommendation.id(), studentId, GeneratedBy.RULE);
        outboxEventWriter.write("recommendations", studentId, event.eventType(), event.eventVersion(), event,
            tenantId, correlationId, null);
        return recommendation;
    }

    public sealed interface DecisionResult {
        record Success(UUID interventionId) implements DecisionResult {
        }

        record NotFound() implements DecisionResult {
        }

        record InvalidTransition() implements DecisionResult {
        }
    }

    @Transactional
    public DecisionResult accept(UUID recommendationId, LocalDate dueDate) {
        Optional<Recommendation> maybeRecommendation = recommendationRepository.findById(recommendationId);
        if (maybeRecommendation.isEmpty()) {
            return new DecisionResult.NotFound();
        }
        Recommendation recommendation = maybeRecommendation.get();
        Optional<RiskScore> maybeScore = riskScoreRepository.findById(recommendation.riskScoreId());
        if (maybeScore.isEmpty()) {
            return new DecisionResult.NotFound();
        }
        RiskScore riskScore = maybeScore.get();
        assertManage(riskScore.tenantId(), riskScore.studentId());
        if (!recommendation.canTransitionTo(RecommendationStatus.ACCEPTED)) {
            return new DecisionResult.InvalidTransition();
        }
        int updated = recommendationRepository.transitionIfStatus(recommendationId, RecommendationStatus.PROPOSED, RecommendationStatus.ACCEPTED);
        if (updated == 0) {
            return new DecisionResult.InvalidTransition();
        }
        UUID interventionId = interventionService.createFromRecommendation(riskScore, recommendation.id(),
            authorizationContext.currentUserId(), dueDate);
        auditLogWriter.write(riskScore.tenantId(), authorizationContext.currentUserId(), "RECOMMENDATION_ACCEPTED",
            "risk.recommendations", recommendationId, Map.of("studentId", riskScore.studentId()));
        return new DecisionResult.Success(interventionId);
    }

    public enum DismissResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public DismissResult dismiss(UUID recommendationId) {
        Optional<Recommendation> maybeRecommendation = recommendationRepository.findById(recommendationId);
        if (maybeRecommendation.isEmpty()) {
            return DismissResult.NOT_FOUND;
        }
        Recommendation recommendation = maybeRecommendation.get();
        Optional<RiskScore> maybeScore = riskScoreRepository.findById(recommendation.riskScoreId());
        if (maybeScore.isEmpty()) {
            return DismissResult.NOT_FOUND;
        }
        RiskScore riskScore = maybeScore.get();
        assertManage(riskScore.tenantId(), riskScore.studentId());
        if (!recommendation.canTransitionTo(RecommendationStatus.DISMISSED)) {
            return DismissResult.INVALID_TRANSITION;
        }
        int updated = recommendationRepository.transitionIfStatus(recommendationId, RecommendationStatus.PROPOSED, RecommendationStatus.DISMISSED);
        if (updated == 0) {
            return DismissResult.INVALID_TRANSITION;
        }
        auditLogWriter.write(riskScore.tenantId(), authorizationContext.currentUserId(), "RECOMMENDATION_DISMISSED",
            "risk.recommendations", recommendationId, Map.of("studentId", riskScore.studentId()));
        return DismissResult.SUCCESS;
    }

    /**
     * Same view scope as the owning risk score ({@link #VIEW_PERMISSION}/
     * {@link #VIEW_PERMISSION_ALL}), authorized against the score's OWN
     * tenant/student/owner — never a client-supplied value — so a {@code
     * riskScoreId} alone cannot be used to read another student's
     * recommendations.
     */
    @Transactional(readOnly = true)
    public List<Recommendation> listForRiskScore(UUID riskScoreId) {
        Optional<RiskScore> maybeScore = riskScoreRepository.findById(riskScoreId);
        if (maybeScore.isEmpty()) {
            return List.of();
        }
        RiskScore riskScore = maybeScore.get();
        assertView(riskScore.tenantId(), riskScore.studentId());
        return recommendationRepository.findByRiskScoreId(riskScoreId);
    }

    private void assertManage(UUID tenantId, UUID studentId) {
        if (accessSupport.currentUserIsAdvisorOf(tenantId, studentId)) {
            authorizationContext.assertCan(MANAGE_PERMISSION,
                ResourceRef.of("recommendation", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (authorizationContext.can(MANAGE_PERMISSION_ALL, ResourceRef.of("recommendation", studentId, tenantId)).isAllowed()) {
            return;
        }
        authorizationContext.assertCan(MANAGE_PERMISSION,
            ResourceRef.of("recommendation", studentId, tenantId).ownedBy(UUID.randomUUID()));
    }

    /**
     * Own/advisor/admin only — deliberately NO guardian-delegation branch.
     * docs/authorization-matrix.md's "Recommendation / intervention" row
     * gives GUARDIAN "—" (no access), unlike the "Risk score / factors"
     * row directly above it which is guardian-delegatable; the two rows
     * differ on purpose and must not share the same view logic.
     */
    private void assertView(UUID tenantId, UUID studentId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION,
                ResourceRef.of("recommendation", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (accessSupport.currentUserIsAdvisorOf(tenantId, studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION,
                ResourceRef.of("recommendation", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        authorizationContext.assertCan(VIEW_PERMISSION_ALL, ResourceRef.of("recommendation", studentId, tenantId));
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #accept} — empty on any non-success outcome. */
    @Transactional
    public java.util.Optional<UUID> acceptForSeed(UUID recommendationId, java.time.LocalDate dueDate) {
        return switch (accept(recommendationId, dueDate)) {
            case DecisionResult.Success s -> java.util.Optional.of(s.interventionId());
            default -> java.util.Optional.empty();
        };
    }

}
