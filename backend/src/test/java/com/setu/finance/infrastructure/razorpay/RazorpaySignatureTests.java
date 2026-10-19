package com.setu.finance.infrastructure.razorpay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook's only defence is its signature, so the check is tested on its own: a known answer (computed independently with Node's crypto
 * module, not by this class), then every way a forged or damaged request could try to pass.
 */
class RazorpaySignatureTests {

    private static final byte[] BODY = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_test";
    /** HMAC-SHA256 of BODY keyed with SECRET, as lowercase hex, from `crypto.createHmac("sha256", ...)` in Node. */
    private static final String KNOWN = "4f463a57dd128675850163391f0311888616d57bccca75c774c9cdb28134f851";

    @Test
    void signMatchesAnIndependentlyComputedValue() {
        assertThat(RazorpaySignature.sign(BODY, SECRET)).isEqualTo(KNOWN);
    }

    @Test
    void aCorrectSignatureVerifies() {
        assertThat(RazorpaySignature.verify(BODY, KNOWN, SECRET)).isTrue();
    }

    @Test
    void hexCaseAndSurroundingSpaceDoNotMatter() {
        assertThat(RazorpaySignature.verify(BODY, " " + KNOWN.toUpperCase() + " ", SECRET)).isTrue();
    }

    @Test
    void aBodyChangedByOneByteFails() {
        byte[] changed = "{\"event\":\"payment.captureD\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(RazorpaySignature.verify(changed, KNOWN, SECRET)).isFalse();
    }

    @Test
    void aReserialisedBodyFails() {
        // The same JSON with a space added is the same data but different bytes: the signature is over bytes.
        byte[] spaced = "{\"event\": \"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(RazorpaySignature.verify(spaced, KNOWN, SECRET)).isFalse();
    }

    @Test
    void theWrongSecretFails() {
        assertThat(RazorpaySignature.verify(BODY, KNOWN, "another-secret")).isFalse();
    }

    @Test
    void aTruncatedOrExtendedSignatureFails() {
        assertThat(RazorpaySignature.verify(BODY, KNOWN.substring(0, KNOWN.length() - 1), SECRET)).isFalse();
        assertThat(RazorpaySignature.verify(BODY, KNOWN + "0", SECRET)).isFalse();
    }

    @Test
    void nothingIsAcceptedWithoutASecretASignatureOrABody() {
        assertThat(RazorpaySignature.verify(BODY, KNOWN, "")).isFalse();
        assertThat(RazorpaySignature.verify(BODY, KNOWN, "  ")).isFalse();
        assertThat(RazorpaySignature.verify(BODY, KNOWN, null)).isFalse();
        assertThat(RazorpaySignature.verify(BODY, null, SECRET)).isFalse();
        assertThat(RazorpaySignature.verify(BODY, "", SECRET)).isFalse();
        assertThat(RazorpaySignature.verify(null, KNOWN, SECRET)).isFalse();
    }

    @Test
    void theSignatureOfAnEmptySecretIsNeverAcceptedEvenIfTheAttackerComputesIt() {
        // If a blank secret were allowed, anyone could sign with the empty key. It is refused before any HMAC is computed.
        String forged = RazorpaySignature.sign(BODY, "x");
        assertThat(RazorpaySignature.verify(BODY, forged, "")).isFalse();
    }
}
