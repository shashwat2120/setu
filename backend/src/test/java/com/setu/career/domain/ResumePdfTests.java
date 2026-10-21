package com.setu.career.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What makes a resume file acceptable: judged by its content, never its name or type, with what a PDF can do to the person who opens it screened out. */
public class ResumePdfTests {

    /** A tiny but well-formed PDF whose catalog carries {@code extra}, so a test can add exactly the feature it is about. */
    public static byte[] pdf(String extra) {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R " + extra + " >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void rejected(byte[] bytes, String reason) {
        assertThatThrownBy(() -> ResumePdf.check(bytes)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(reason);
    }

    @Test
    void aWellFormedPdfIsAccepted() {
        assertThatCode(() -> ResumePdf.check(pdf(""))).doesNotThrowAnyException();
        assertThatCode(() -> ResumePdf.check(pdf("/OpenAction [3 0 R /Fit] /Lang (en)"))).as("an ordinary open action is how most PDFs set their zoom").doesNotThrowAnyException();
    }

    @Test
    void whatIsNotAPdfIsRefusedWhateverItIsCalled() {
        rejected(new byte[0], "empty");
        rejected("PK\u0003\u0004 a zip, or a docx, renamed resume.pdf padded out to be long enough".getBytes(StandardCharsets.ISO_8859_1), "only PDF");
        rejected(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "only PDF");
        rejected("#!/bin/sh\necho %PDF- not at the start, so not a pdf at all\n%%EOF".getBytes(StandardCharsets.ISO_8859_1), "only PDF");
        rejected("%PDF-1.4\nstarts like a pdf but never ends like one, and goes on for a little while".getBytes(StandardCharsets.ISO_8859_1), "damaged or cut short");
    }

    @Test
    void aFileOverTwoMegabytesIsRefused() {
        byte[] big = Arrays.copyOf(pdf(""), ResumePdf.MAX_BYTES + 1);

        rejected(big, "larger than 2 MB");
        byte[] exactly = new byte[ResumePdf.MAX_BYTES];
        byte[] body = pdf("");
        System.arraycopy(body, 0, exactly, 0, body.length);
        System.arraycopy(body, 0, exactly, exactly.length - body.length, body.length);
        assertThatCode(() -> ResumePdf.check(exactly)).doesNotThrowAnyException();
    }

    @Test
    void anythingThatCanRunOrFetchSomethingWhenViewedIsRefused() {
        for (String feature : new String[] {"/JavaScript 5 0 R", "/JS (app.alert(1))", "/Launch << /F (cmd.exe) >>", "/EmbeddedFile 7 0 R", "/EmbeddedFiles 7 0 R", "/RichMedia 9 0 R",
            "/XFA 4 0 R", "/SubmitForm << /F (https://evil.example) >>", "/GoToR << /F (other.pdf) >>"}) {
            rejected(pdf(feature), "features a resume must not");
        }
    }

    @Test
    void aNameSpelledWithHexEscapesIsStillTheNameItHides() {
        rejected(pdf("/J#61vaScript 5 0 R"), "features a resume must not");
        rejected(pdf("/#4AS (x)"), "features a resume must not");
    }

    @Test
    void anEncryptedPdfIsRefusedWithAnExplanationNobodyCouldReadIt() {
        rejected(pdf("/Encrypt 8 0 R"), "password-protected");
    }

    @Test
    void theBinaryBodiesOfStreamsAreNotScannedForNames() {
        byte[] withStream = ("%PDF-1.4\n1 0 obj\n<< /Length 30 >>\nstream\nrandom bytes that happen to spell /JS and /Launch here\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
            .getBytes(StandardCharsets.ISO_8859_1);

        assertThatCode(() -> ResumePdf.check(withStream)).doesNotThrowAnyException();
    }

    @Test
    void aStreamThatIsNeverClosedMakesTheFileDamagedAndManyOfThemAreCheckedQuickly() {
        StringBuilder many = new StringBuilder("%PDF-1.4\n");
        for (int i = 0; i < 100_000; i++) {
            many.append("stream\n");
        }
        many.append("\n%%EOF\n");
        long started = System.nanoTime();

        rejected(many.toString().getBytes(StandardCharsets.ISO_8859_1), "damaged or cut short");

        assertThat((System.nanoTime() - started) / 1_000_000).as("milliseconds: one pass over the file, not one per marker").isLessThan(2000);
    }

    @Test
    void theHashIsTheContentsSha256() {
        assertThat(ResumePdf.sha256("abc".getBytes(StandardCharsets.US_ASCII))).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ResumePdf.sha256(pdf("a"))).isNotEqualTo(ResumePdf.sha256(pdf("b"))).hasSize(64);
    }

    @Test
    void theNameShownIsCleanedAndNeverAPath() {
        assertThat(ResumePdf.displayName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(ResumePdf.displayName("C:\\Users\\me\\My Resume (final).PDF")).isEqualTo("My Resume (final).pdf");
        assertThat(ResumePdf.displayName("r\u00e9sum\u00e9<script>.pdf")).isEqualTo("r_sum__script_.pdf");
        assertThat(ResumePdf.displayName("cv")).isEqualTo("cv.pdf");
        assertThat(ResumePdf.displayName(null)).isEqualTo("resume.pdf");
        assertThat(ResumePdf.displayName("   ")).isEqualTo("resume.pdf");
        assertThat(ResumePdf.displayName(".hidden.pdf")).isEqualTo("hidden.pdf");
        assertThat(ResumePdf.displayName("x".repeat(300) + ".pdf")).hasSize(100).endsWith(".pdf");
    }
}
