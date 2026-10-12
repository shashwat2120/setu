package com.setu.learning.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a lesson or resource reference may point at: real https links and the internal ref:// and s3:// forms only. */
class ContentReferencesTests {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.youtube.com/watch?v=JP7ITIXGpHk",
        "https://youtu.be/JP7ITIXGpHk",
        "https://docs.python.org/3/tutorial/introduction.html",
        "https://greenteapress.com/thinkpython2/thinkpython2.pdf",
        "HTTPS://Example.org/a",
        "ref://cs101/lesson1",
        "s3://bucket/slides.pdf"
    })
    void acceptsRealLinksAndInternalReferences(String ref) {
        assertThat(ContentReferences.required(ref)).isEqualTo(ref);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://example.org/plain",
        "javascript:alert(1)",
        "data:text/html;base64,PHNjcmlwdD4=",
        "file:///etc/passwd",
        "ftp://example.org/file",
        "//example.org/protocol-relative",
        "/relative/path",
        "https://",
        "https:///no-host",
        "https://user:secret@example.org/",
        "https://example.org/has space",
        "https://example.org/tab\there",
        "not a link"
    })
    void rejectsUnsafeOrMalformedReferences(String ref) {
        assertThatThrownBy(() -> ContentReferences.required(ref)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongReference() {
        String tooLong = "https://example.org/" + "a".repeat(ContentReferences.MAX_LENGTH);
        assertThatThrownBy(() -> ContentReferences.required(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredRejectsBlankAndNull() {
        assertThatThrownBy(() -> ContentReferences.required("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContentReferences.required(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionalTreatsBlankAsAbsentButStillChecksPresentValues() {
        assertThat(ContentReferences.optional(null)).isNull();
        assertThat(ContentReferences.optional("   ")).isNull();
        assertThat(ContentReferences.optional("  https://youtu.be/JP7ITIXGpHk  ")).isEqualTo("https://youtu.be/JP7ITIXGpHk");
        assertThatThrownBy(() -> ContentReferences.optional("javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void titleIsTrimmedBlankBecomesNullAndLengthIsBounded() {
        assertThat(ContentReferences.title("  Think Python (PDF) ")).isEqualTo("Think Python (PDF)");
        assertThat(ContentReferences.title("   ")).isNull();
        assertThat(ContentReferences.title(null)).isNull();
        assertThat(ContentReferences.title("t".repeat(ContentReferences.MAX_TITLE_LENGTH))).hasSize(ContentReferences.MAX_TITLE_LENGTH);
        assertThatThrownBy(() -> ContentReferences.title("t".repeat(ContentReferences.MAX_TITLE_LENGTH + 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
