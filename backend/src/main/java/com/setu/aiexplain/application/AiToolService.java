package com.setu.aiexplain.application;

import com.setu.shared.aifacts.AiFacts;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The advisor's tools, as the model sees them: a name, a description and parameters, nothing else (canonical spec section 10 / TRD section 6).
 * Every tool is one call through {@link AiFacts}, the secure tool boundary. This class holds <b>no data access and no authorization of its
 * own</b>, and cannot: the module has no datasource and depends on no other module. The model supplies only which student a question is about
 * (or nothing, meaning the caller); whether the caller may see the answer is decided where the data is, for the caller's own identity,
 * whether that is in this process or across the network (docs/contracts/ai-service.md).
 * <p>
 * If a fact cannot be obtained because its owner is unavailable, the tool says so in a structured {@link ToolError}; it never returns an empty
 * or invented result, so the model can only tell the user the truth: the data is not available right now.
 */
@Component
public class AiToolService {

    /** What the model gets when a fact could not be obtained at all (as opposed to the owner refusing, which comes back from the owner). */
    public record ToolError(String reason) {
    }

    private final AiFacts facts;
    private final AiToolInvocationRecorder invocationRecorder;

    public AiToolService(AiFacts facts, AiToolInvocationRecorder invocationRecorder) {
        this.facts = facts;
        this.invocationRecorder = invocationRecorder;
    }

    private Object invoke(String toolName, Supplier<Object> fact) {
        invocationRecorder.record(toolName);
        try {
            return fact.get();
        } catch (AiFacts.Unavailable e) {
            return new ToolError("this information is not available right now; please try again shortly");
        }
    }

    @Tool(description = "Get a student's overall attendance percentage across all their currently enrolled sections. "
        + "Omit studentId to get the current user's own attendance.")
    public Object getStudentAttendance(
        @ToolParam(description = "Student profile UUID, or omit for the caller's own attendance", required = false) String studentId) {
        return invoke("getStudentAttendance", () -> facts.studentAttendance(studentId));
    }

    @Tool(description = "Get a student's current academic progress: published grades per assessment component, "
        + "and their current Student Success risk assessment (band + top factors) if one has been calculated. "
        + "Omit studentId for the caller's own progress.")
    public Object getAcademicProgress(
        @ToolParam(description = "Student profile UUID, or omit for the caller's own progress", required = false) String studentId) {
        return invoke("getAcademicProgress", () -> facts.academicProgress(studentId));
    }

    @Tool(description = "Get a student's current published grade for every assessment component they have a result for. "
        + "Omit studentId for the caller's own results.")
    public Object getAssessmentResults(
        @ToolParam(description = "Student profile UUID, or omit for the caller's own results", required = false) String studentId) {
        return invoke("getAssessmentResults", () -> facts.assessmentResults(studentId));
    }

    @Tool(description = "Get a student's upcoming assignment and assessment deadlines across all their currently "
        + "enrolled sections, soonest first. Omit studentId for the caller's own deadlines.")
    public Object getUpcomingDeadlines(
        @ToolParam(description = "Student profile UUID, or omit for the caller's own deadlines", required = false) String studentId) {
        return invoke("getUpcomingDeadlines", () -> facts.upcomingDeadlines(studentId));
    }

    @Tool(description = "Compare a student's current skill profile against a specific job's required skills and "
        + "return which skills are missing or below the required proficiency. Requires jobId. Omit studentId for "
        + "the caller's own skill gap (career data has no advisor/guardian access — self or admin only).")
    public Object getSkillGap(
        @ToolParam(description = "Job UUID to check eligibility against") String jobId,
        @ToolParam(description = "Student profile UUID, or omit for the caller's own skill gap", required = false) String studentId) {
        return invoke("getSkillGap", () -> facts.skillGap(jobId, studentId));
    }

    @Tool(description = "Search this college's own policy documents (e.g. attendance policy, grading policy) for "
        + "text relevant to the given query. Always scoped to the caller's own tenant — never accepts a tenant as "
        + "input.")
    public Object searchCollegePolicy(
        @ToolParam(description = "Free-text search query, e.g. 'attendance policy'") String query) {
        return invoke("searchCollegePolicy", () -> facts.policySearch(query));
    }

    @Tool(description = "Recommend existing learning resources (lessons) the student has not yet completed, drawn "
        + "only from lessons that actually exist in their enrolled sections — never an invented course or lesson. "
        + "Omit studentId for the caller's own recommendations.")
    public Object recommendLearningResources(
        @ToolParam(description = "Student profile UUID, or omit for the caller's own recommendations", required = false) String studentId) {
        return invoke("recommendLearningResources", () -> facts.learningResources(studentId));
    }

    @Tool(description = "Get a student's academic transcript: every course with its credits, enrollment status and "
        + "course grade point (weighted across its assessment components), the overall credit-weighted GPA to date, and "
        + "degree progress (credits completed vs. required). Omit studentId for the caller's own transcript.")
    public Object getTranscript(
        @ToolParam(description = "Student profile UUID, or omit for the caller's own transcript", required = false) String studentId) {
        return invoke("getTranscript", () -> facts.transcript(studentId));
    }

    @Tool(description = "Get a student's fee charges (amount, due date, status) and the total still outstanding "
        + "(charges that are pending or overdue). Omit studentId for the caller's own fees.")
    public Object getFeeStatus(
        @ToolParam(description = "Student profile UUID, or omit for the caller's own fees", required = false) String studentId) {
        return invoke("getFeeStatus", () -> facts.feeStatus(studentId));
    }
}
