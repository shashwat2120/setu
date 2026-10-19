package com.setu.aiexplain.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.JsonParser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model is shown for a fact fetched from another service. As a raw JSON tree it saw a record with no fields and answered "no data"; as plain
 * maps and lists it sees the numbers. The assertions use the serializer the tool-calling layer uses for results.
 */
class AiToolResultForModelTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aFactFromAnotherServiceIsShownToTheModelWithItsFields() throws Exception {
        var tree = mapper.readTree("{\"percentage\":50.0,\"presentOrLate\":4,\"total\":8}");

        String shown = JsonParser.toJson(AiToolService.forModel(tree));

        assertThat(shown).contains("\"percentage\":50.0").contains("\"presentOrLate\":4").contains("\"total\":8");
    }

    @Test
    void aListOfFactsKeepsEveryItem() throws Exception {
        var tree = mapper.readTree("[{\"gradeCode\":\"D\"},{\"gradeCode\":\"C+\"}]");

        String shown = JsonParser.toJson(AiToolService.forModel(tree));

        assertThat(shown).contains("\"gradeCode\":\"D\"").contains("\"gradeCode\":\"C+\"");
    }

    @Test
    void anEmptyAnswerIsNullAndAnythingElseIsLeftAlone() throws Exception {
        assertThat(AiToolService.forModel(mapper.nullNode())).isNull();
        assertThat(AiToolService.forModel(Map.of("a", 1))).isEqualTo(Map.of("a", 1));
        assertThat(AiToolService.forModel(List.of("x"))).isEqualTo(List.of("x"));
    }
}
