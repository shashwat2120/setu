package com.setu.testsupport;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.application.AssessmentService;
import com.setu.assessment.application.QuestionService;
import com.setu.assessment.domain.QuestionType;
import com.setu.identity.TestAuthentication;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Day 11 test fixtures — builds on Day 7-10 fixtures for tenant/section/
 * faculty/student, and adds assessment-specific provisioning: a fully
 * scheduled-and-opened assessment with one component and one auto-gradable
 * MCQ question, ready for a student to attempt immediately.
 */
@Component
public class Day11TestFixtures {

    private final AssessmentService assessmentService;
    private final AssessmentComponentService assessmentComponentService;
    private final QuestionService questionService;

    public Day11TestFixtures(AssessmentService assessmentService, AssessmentComponentService assessmentComponentService,
                              QuestionService questionService) {
        this.assessmentService = assessmentService;
        this.assessmentComponentService = assessmentComponentService;
        this.questionService = questionService;
    }

    public record OpenAssessment(UUID assessmentId, UUID componentId, UUID questionId) {
    }

    /** Creates an assessment, one component (weight 100), one MCQ_SINGLE question (correctOption "B", 10 points), schedules and opens it — ready for a student to start an attempt. All as the given faculty/admin userId. */
    public OpenAssessment provisionOpenAssessment(UUID actingUserId, UUID sectionId, String title) {
        TestAuthentication.authenticateAs(actingUserId);
        try {
            var created = (AssessmentService.CreateResult.Success) assessmentService.create(
                sectionId, "QUIZ", title, new BigDecimal("10"), 30, Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS));
            UUID assessmentId = created.assessmentId();

            var component = (AssessmentComponentService.CreateResult.Success) assessmentComponentService.create(
                assessmentId, "C1", new BigDecimal("10"), new BigDecimal("100"), 1);
            UUID componentId = component.componentId();

            var question = (QuestionService.CreateResult.Success) questionService.create(
                componentId, QuestionType.MCQ_SINGLE, "What is 2+2?", new BigDecimal("10"), "EASY", null,
                "{\"correctOption\": \"B\"}");
            UUID questionId = question.questionId();

            assessmentService.schedule(assessmentId);
            assessmentService.open(assessmentId);

            return new OpenAssessment(assessmentId, componentId, questionId);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** Same as {@link #provisionOpenAssessment} but leaves the assessment in DRAFT (no component/question, no schedule/open) — for lifecycle/validation tests that need to drive transitions themselves. */
    public UUID provisionDraftAssessment(UUID actingUserId, UUID sectionId, String title, Instant startsAt, Instant endsAt) {
        TestAuthentication.authenticateAs(actingUserId);
        try {
            var created = (AssessmentService.CreateResult.Success) assessmentService.create(
                sectionId, "QUIZ", title, new BigDecimal("10"), 30, startsAt, endsAt);
            return created.assessmentId();
        } finally {
            TestAuthentication.clear();
        }
    }
}
