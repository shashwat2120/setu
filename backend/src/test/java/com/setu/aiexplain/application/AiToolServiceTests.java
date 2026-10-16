package com.setu.aiexplain.application;

import com.setu.shared.aifacts.AiFacts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The model-facing tool layer is thin on purpose: each tool is one call through {@link AiFacts} and nothing more. These tests pin that down: the
 * model sees exactly the nine tools it always did, none of them takes anything that could say who is asking or from which college, every call
 * is forwarded as given, and a fact that could not be obtained becomes an honest structured error, never an empty or invented result.
 */
class AiToolServiceTests {

    private static final Set<String> TOOLS = Set.of("getStudentAttendance", "getAcademicProgress", "getAssessmentResults", "getUpcomingDeadlines",
        "getSkillGap", "searchCollegePolicy", "recommendLearningResources", "getTranscript", "getFeeStatus");

    private final AiFacts facts = mock(AiFacts.class);
    private final AiToolInvocationRecorder recorder = new AiToolInvocationRecorder();
    private final AiToolService tools = new AiToolService(facts, recorder);

    @Test
    void theModelSeesTheSameNineToolsAndNoneOfThemTakesAnIdentityOrACollege() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name())).containsExactlyInAnyOrderElementsOf(TOOLS);
        for (ToolCallback callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema().toLowerCase();
            assertThat(schema).as(callback.getToolDefinition().name()).doesNotContain("tenant", "userid", "token", "role");
        }
    }

    @Test
    void eachToolIsOneCallThroughTheBoundaryWithTheArgumentsItWasGiven() {
        when(facts.studentAttendance(any())).thenReturn("attendance");
        when(facts.skillGap(any(), any())).thenReturn("gap");
        when(facts.policySearch(any())).thenReturn("policy");

        assertThat(tools.getStudentAttendance("s-1")).isEqualTo("attendance");
        assertThat(tools.getSkillGap("job-9", null)).isEqualTo("gap");
        assertThat(tools.searchCollegePolicy("grading policy")).isEqualTo("policy");

        verify(facts).studentAttendance("s-1");
        verify(facts).skillGap("job-9", null);
        verify(facts).policySearch("grading policy");
        verifyNoMoreInteractions(facts);
        assertThat(recorder.drain()).as("sources for the answer").containsExactly("getStudentAttendance", "getSkillGap", "searchCollegePolicy");
    }

    @Test
    void aFactThatCannotBeObtainedIsAnHonestErrorForTheModelToReport() {
        when(facts.transcript(any())).thenThrow(new AiFacts.Unavailable("the data service did not answer (circuit_open)", null));
        when(facts.feeStatus(any())).thenThrow(new AiFacts.Unavailable("timed out", null));

        Object transcript = tools.getTranscript(null);
        Object fees = tools.getFeeStatus("s-2");

        assertThat(transcript).isInstanceOfSatisfying(AiToolService.ToolError.class,
            e -> assertThat(e.reason()).contains("not available right now").doesNotContain("circuit", "timed out"));
        assertThat(fees).isInstanceOf(AiToolService.ToolError.class);
        assertThat(recorder.drain()).as("the attempt is still a source the user can see").containsExactly("getTranscript", "getFeeStatus");
    }

    @Test
    void everyToolHasADescriptionTheModelCanUse() {
        List<String> blank = Arrays.stream(ToolCallbacks.from(tools))
            .filter(c -> c.getToolDefinition().description() == null || c.getToolDefinition().description().length() < 40)
            .map(c -> c.getToolDefinition().name()).toList();
        assertThat(blank).isEmpty();
    }
}
