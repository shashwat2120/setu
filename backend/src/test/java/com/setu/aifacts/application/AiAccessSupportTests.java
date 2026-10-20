package com.setu.aifacts.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model that is told an argument is optional sometimes fills it with a word for "nothing". That must mean "no student given" (the caller's own profile, the
 * narrowest scope there is) and must never become another person's data; anything that is not one of those words is still treated as a candidate id and
 * authorized as before.
 */
class AiAccessSupportTests {

    @Test
    void theWordsAModelWritesForNothingMeanNoStudentGiven() {
        for (String word : new String[]{null, "", "   ", "None", "none", "NULL", "null", "undefined", "N/A", "na", "me", "Self", "myself", "current", "caller", " None "}) {
            assertThat(AiAccessSupport.isNoStudentGiven(word)).as("'%s'", word).isTrue();
        }
    }

    @Test
    void anythingElseIsStillACandidateIdToBeAuthorized() {
        for (String candidate : new String[]{"3f2c1d3e-1111-4222-8333-444455556666", "someone-else", "all", "everyone", "*", "0", "None of them"}) {
            assertThat(AiAccessSupport.isNoStudentGiven(candidate)).as("'%s'", candidate).isFalse();
        }
    }
}
