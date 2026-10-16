package com.setu.aifacts.application;

import com.setu.shared.aifacts.AiFacts;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * {@link AiFacts} answered in this process, for the current request's principal. This is the whole of what the advisor used to do inline:
 * the same services, the same checks, the same audit writer. It is also what answers the {@code /ai-facts/**} endpoints, so a remote advisor
 * and an in-process one reach exactly one implementation of the rules.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${setu.ai-facts.url:}'.isEmpty()")
public class InProcessAiFacts implements AiFacts {

    private final AiFactsService facts;
    private final AuthorizationContext authorizationContext;
    private final TenantContext tenantContext;
    private final AuditLogWriter auditLogWriter;

    public InProcessAiFacts(AiFactsService facts, AuthorizationContext authorizationContext, TenantContext tenantContext,
                            AuditLogWriter auditLogWriter) {
        this.facts = facts;
        this.authorizationContext = authorizationContext;
        this.tenantContext = tenantContext;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public Caller caller() {
        return new Caller(authorizationContext.currentUserId(), tenantContext.tenantId().orElse(null));
    }

    @Override
    public Object studentAttendance(String studentId) {
        return facts.getStudentAttendance(studentId);
    }

    @Override
    public Object academicProgress(String studentId) {
        return facts.getAcademicProgress(studentId);
    }

    @Override
    public Object assessmentResults(String studentId) {
        return facts.getAssessmentResults(studentId);
    }

    @Override
    public Object upcomingDeadlines(String studentId) {
        return facts.getUpcomingDeadlines(studentId);
    }

    @Override
    public Object skillGap(String jobId, String studentId) {
        return facts.getSkillGap(jobId, studentId);
    }

    @Override
    public Object policySearch(String query) {
        return facts.searchCollegePolicy(query);
    }

    @Override
    public Object learningResources(String studentId) {
        return facts.recommendLearningResources(studentId);
    }

    @Override
    public Object transcript(String studentId) {
        return facts.getTranscript(studentId);
    }

    @Override
    public Object feeStatus(String studentId) {
        return facts.getFeeStatus(studentId);
    }

    @Override
    public void audit(String action, UUID correlationId, Map<String, Object> details) {
        auditLogWriter.write(tenantContext.tenantId().orElse(null), authorizationContext.currentUserId(), action, "ai_chat", correlationId, details);
    }
}
