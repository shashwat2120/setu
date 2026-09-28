package com.setu.risk.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.directory.application.StudentProfileService;
import com.setu.risk.domain.RiskBand;
import com.setu.risk.domain.RiskFactor;
import com.setu.risk.domain.RiskScore;
import com.setu.risk.domain.RiskScoreChangedEvent;
import com.setu.risk.infrastructure.RiskFactorRepository;
import com.setu.risk.infrastructure.RiskScoreRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Day 13 §Student Success Engine: risk_scores/risk_factors — the
 * deterministic engine's write side, and the ONLY thing authoritative for
 * persisted risk state (canonical spec's explicit "AI is NOT authoritative"
 * rule — this class has no AI dependency of any kind, and {@code
 * ai-explain} does not exist as a real caller until Day 14).
 * <p>
 * state-machines.md #21 (Risk Score): append-only, no lifecycle. A new
 * calculation always inserts a new row; {@link #recalculate} never updates
 * an existing one. {@code RiskScoreChanged} is emitted to the outbox only
 * when the new row's {@code band} differs from the previous current row's
 * — never on every recalculation — matching the state machine's own
 * explicit "avoid alert fatigue" note.
 * <p>
 * Authorization for triggering a recalculation is bundled under {@code
 * MANAGE_INTERVENTIONS}/{@code MANAGE_INTERVENTIONS_ALL_SECTIONS} rather
 * than a separate code — the authorization matrix has no distinct
 * "trigger calculation" row of its own; computing a fresh score is the
 * natural precursor to managing recommendations/interventions for that
 * student, so it shares that row's FACULTY-own-advisees /
 * COLLEGE_ADMIN-tenant-full shape. This is a disclosed interpretation, not
 * a literal transcription of an explicit matrix cell.
 * <p>
 * {@code @NamedInterface} added Day 14: {@code aiexplain.application}'s
 * {@code getAcademicProgress} tool reuses {@link #getCurrent}/{@link
 * #factorsFor} AS-IS to explain a student's existing risk state — those
 * methods already have exactly the right self/advisor/admin/guardian
 * ({@code VIEW_RISK_SCORE}) authorization, so no new port was needed. AI
 * never calls {@link #recalculate} — it only ever reads an already-
 * computed, deterministic result (canonical spec §13's rule that AI
 * explains a risk result rather than deciding one).
 */
@org.springframework.modulith.NamedInterface
@Service
public class RiskCalculationService {

    private static final String MANAGE_PERMISSION = "MANAGE_INTERVENTIONS";
    private static final String MANAGE_PERMISSION_ALL = "MANAGE_INTERVENTIONS_ALL_SECTIONS";
    private static final String VIEW_PERMISSION = "VIEW_RISK_SCORE";
    private static final String VIEW_PERMISSION_ALL = "VIEW_RISK_SCORE_ALL_SECTIONS";

    private final RiskScoreRepository riskScoreRepository;
    private final RiskFactorRepository riskFactorRepository;
    private final RiskFeatureService riskFeatureService;
    private final RiskScoringEngine riskScoringEngine;
    private final StudentProfileService studentProfileService;
    private final RiskAccessSupport accessSupport;
    private final RecommendationService recommendationService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;
    private final ObjectMapper objectMapper;

    public RiskCalculationService(RiskScoreRepository riskScoreRepository, RiskFactorRepository riskFactorRepository,
                                   RiskFeatureService riskFeatureService, RiskScoringEngine riskScoringEngine,
                                   StudentProfileService studentProfileService, RiskAccessSupport accessSupport,
                                   RecommendationService recommendationService, AuthorizationContext authorizationContext,
                                   AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter, ObjectMapper objectMapper) {
        this.riskScoreRepository = riskScoreRepository;
        this.riskFactorRepository = riskFactorRepository;
        this.riskFeatureService = riskFeatureService;
        this.riskScoringEngine = riskScoringEngine;
        this.studentProfileService = studentProfileService;
        this.accessSupport = accessSupport;
        this.recommendationService = recommendationService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
        this.objectMapper = objectMapper;
    }

    public sealed interface RecalculateResult {
        record Success(UUID riskScoreId, RiskBand band, boolean bandChanged) implements RecalculateResult {
        }

        record StudentNotFound() implements RecalculateResult {
        }
    }

    @Transactional
    public RecalculateResult recalculate(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new RecalculateResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        assertManage(tenantId, studentId);

        RiskFeatureService.FeatureSnapshot snapshot = riskFeatureService.computeFor(studentId);
        RiskScoringEngine.Result result = riskScoringEngine.calculate(snapshot);

        String featureJson = writeFeatureSnapshotJson(snapshot);
        String hash = sha256Hex(featureJson);
        Instant now = Instant.now();

        Optional<RiskScore> previous = riskScoreRepository.findFirstByStudentIdOrderByCalculatedAtDesc(studentId);

        RiskScore riskScore = new RiskScore(UUID.randomUUID(), tenantId, studentId, result.score(), result.band(),
            RiskScoringEngine.RULE_VERSION, hash, featureJson, now);
        riskScoreRepository.saveAndFlush(riskScore);

        for (RiskScoringEngine.FactorResult factor : result.factors()) {
            String evidenceJson = writeEvidenceJson(snapshot, factor.factorCode());
            RiskFactor riskFactor = new RiskFactor(UUID.randomUUID(), riskScore.id(), factor.factorCode(),
                factor.contribution(), evidenceJson);
            riskFactorRepository.save(riskFactor);
        }

        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "RISK_SCORE_CALCULATED",
            "risk.risk_scores", riskScore.id(), Map.of("studentId", studentId, "band", result.band().name(),
                "score", result.score()));

        boolean bandChanged = previous.isEmpty() || previous.get().band() != result.band();
        if (bandChanged) {
            UUID correlationId = UUID.randomUUID();
            var event = new RiskScoreChangedEvent(UUID.randomUUID(), now, tenantId, studentId, correlationId, null,
                riskScore.id(), studentId, previous.map(RiskScore::band).orElse(null), result.band(), RiskScoringEngine.RULE_VERSION);
            outboxEventWriter.write("risk_scores", studentId, event.eventType(), event.eventVersion(), event,
                tenantId, correlationId, null);

            // Auto-propose a RULE recommendation only when the band newly rises into MEDIUM/HIGH — never on every
            // recalculation (same "avoid alert fatigue" rationale as the RiskScoreChanged event above), and never
            // when the band drops back to LOW (nothing to act on).
            String actionCode = switch (result.band()) {
                case HIGH -> "URGENT_ADVISOR_MEETING";
                case MEDIUM -> "SCHEDULE_ADVISOR_CHECKIN";
                case LOW -> null;
            };
            if (actionCode != null) {
                recommendationService.proposeFromRule(riskScore.id(), tenantId, studentId, actionCode);
            }
        }

        return new RecalculateResult.Success(riskScore.id(), result.band(), bandChanged);
    }

    /** Own (student, VIEW_RISK_SCORE — also guardian-delegatable), advisor (FACULTY, advisee-scoped), or admin (VIEW_RISK_SCORE_ALL_SECTIONS). */
    @Transactional(readOnly = true)
    public Optional<RiskScore> getCurrent(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return Optional.empty();
        }
        assertView(studentInfo.get().tenantId(), studentId, studentInfo.get().ownerUserId());
        return riskScoreRepository.findFirstByStudentIdOrderByCalculatedAtDesc(studentId);
    }

    /**
     * Day 16 §college-admin: the current (most recent) risk score for every student in a tenant — the
     * institution-wide risk view's data source. {@link RiskScoreRepository} has no per-student "latest" grouping
     * query, so this collapses the tenant's full, newest-first row list down to one row per student in Java —
     * fine at this dataset's scale, and avoids a bespoke window-function query for a single admin-dashboard read.
     * Tenant-wide, so gated on {@link #VIEW_PERMISSION_ALL} only — no self/advisor bypass, matching the other
     * ALL_SECTIONS-only endpoints in this class family.
     */
    @Transactional(readOnly = true)
    public List<RiskScore> listCurrentForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION_ALL, ResourceRef.of("risk_score", UUID.randomUUID(), tenantId));
        List<RiskScore> all = riskScoreRepository.findByTenantIdOrderByCalculatedAtDesc(tenantId);
        Map<UUID, RiskScore> latestByStudent = new java.util.LinkedHashMap<>();
        for (RiskScore score : all) {
            latestByStudent.putIfAbsent(score.studentId(), score);
        }
        return List.copyOf(latestByStudent.values());
    }

    @Transactional(readOnly = true)
    public List<RiskScore> history(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return List.of();
        }
        assertView(studentInfo.get().tenantId(), studentId, studentInfo.get().ownerUserId());
        return riskScoreRepository.findByStudentIdOrderByCalculatedAtDesc(studentId);
    }

    /** No independent authorization — callers already resolved and authorized the owning {@link RiskScore} via {@link #getCurrent}/{@link #history}/{@link #getById}. */
    @Transactional(readOnly = true)
    public List<RiskFactor> factorsFor(UUID riskScoreId) {
        return riskFactorRepository.findByRiskScoreIdOrderByContributionDesc(riskScoreId);
    }

    /**
     * Fetch one score by its own id, authorized against the row's OWN
     * student/tenant (never a client-supplied studentId) — this is what
     * makes it safe to expose {@code riskScoreId} directly in a URL path
     * without an IDOR gap: the id alone cannot be used to read another
     * student's score, since authorization is derived from the row itself.
     */
    @Transactional(readOnly = true)
    public Optional<RiskScore> getById(UUID riskScoreId) {
        Optional<RiskScore> maybe = riskScoreRepository.findById(riskScoreId);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        RiskScore riskScore = maybe.get();
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(riskScore.studentId());
        if (studentInfo.isEmpty()) {
            return Optional.empty();
        }
        assertView(riskScore.tenantId(), riskScore.studentId(), studentInfo.get().ownerUserId());
        return maybe;
    }

    Optional<RiskScore> findById(UUID riskScoreId) {
        return riskScoreRepository.findById(riskScoreId);
    }

    void assertManage(UUID tenantId, UUID studentId) {
        if (accessSupport.currentUserIsAdvisorOf(tenantId, studentId)) {
            authorizationContext.assertCan(MANAGE_PERMISSION,
                ResourceRef.of("risk_score", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (authorizationContext.can(MANAGE_PERMISSION_ALL, ResourceRef.of("risk_score", studentId, tenantId)).isAllowed()) {
            return;
        }
        // Neither an advisor nor a tenant-wide manager — fail with the narrower, role-scoped permission so the
        // denial reason reflects the actual gap (own-advisee scope), matching AssessmentAccessSupport's precedent.
        authorizationContext.assertCan(MANAGE_PERMISSION,
            ResourceRef.of("risk_score", studentId, tenantId).ownedBy(UUID.randomUUID()));
    }

    private void assertView(UUID tenantId, UUID studentId, UUID ownerUserId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION,
                ResourceRef.of("risk_score", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (accessSupport.currentUserIsAdvisorOf(tenantId, studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION,
                ResourceRef.of("risk_score", studentId, tenantId).ownedBy(authorizationContext.currentUserId()));
            return;
        }
        if (authorizationContext.can(VIEW_PERMISSION_ALL, ResourceRef.of("risk_score", studentId, tenantId)).isAllowed()) {
            return;
        }
        ResourceRef delegated = ResourceRef.of("risk_score", studentId, tenantId)
            .ownedBy(ownerUserId).delegatableForStudent(studentId);
        authorizationContext.assertCan(VIEW_PERMISSION, delegated);
    }

    private String writeFeatureSnapshotJson(RiskFeatureService.FeatureSnapshot snapshot) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            for (RiskFeatureService.Feature feature : snapshot.features()) {
                canonical.put(feature.code(), feature.rawValue());
            }
            canonical.put("analyzedEnrollmentIds", snapshot.analyzedEnrollmentIds().stream().sorted().toList());
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize feature snapshot", e);
        }
    }

    private String writeEvidenceJson(RiskFeatureService.FeatureSnapshot snapshot, String factorCode) {
        try {
            List<Map<String, Object>> refs = snapshot.features().stream()
                .filter(f -> f.code().equals(factorCode))
                .findFirst()
                .map(f -> f.evidenceRefs().stream()
                    .map(r -> (Map<String, Object>) Map.<String, Object>of("table", r.table(), "id", r.id().toString()))
                    .toList())
                .orElse(List.of());
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize evidence refs", e);
        }
    }

    private String sha256Hex(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #recalculate} — empty only if the student cannot be found (never happens for a just-admitted seed student). Band/factors are read back afterwards via {@link #getCurrent}, itself already returning a plain domain entity. */
    @Transactional
    public java.util.Optional<UUID> recalculateForSeed(UUID studentId) {
        return switch (recalculate(studentId)) {
            case RecalculateResult.Success s -> java.util.Optional.of(s.riskScoreId());
            case RecalculateResult.StudentNotFound ignored -> java.util.Optional.empty();
        };
    }

}
