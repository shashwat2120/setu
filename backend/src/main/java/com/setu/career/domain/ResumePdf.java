package com.setu.career.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a resume file must be to be accepted: a PDF of at most 2 MB, judged by what is in it and never by the name or type the sender gave it. It must start like a PDF, end
 * like one, not be encrypted (nobody could read it), and not carry the parts of a PDF that can run or open something when it is viewed: scripts, launch actions, embedded
 * files, forms that submit, links to other documents.
 *
 * <p>This is a screen, not a guarantee: a name hidden inside a compressed part of the file cannot be seen without opening the file. The file is therefore never shown inside
 * the application: it is only ever handed to a person as a download, with its type fixed and sniffing switched off, to be opened by their own PDF viewer.
 */
public final class ResumePdf {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_NAME = 100;
    private static final byte[] MAGIC = "%PDF-".getBytes(StandardCharsets.ISO_8859_1);
    private static final int EOF_WINDOW = 1024;
    /** Names (without the slash) of PDF features that run code, fetch or open other things, or hide the content. */
    private static final Set<String> FORBIDDEN = Set.of("JavaScript", "JS", "Launch", "EmbeddedFile", "EmbeddedFiles", "RichMedia", "XFA", "SubmitForm", "ImportData",
        "GoToR", "GoToE", "Encrypt");
    private static final Pattern NAME = Pattern.compile("/([A-Za-z0-9#_.\\-]{1,40})");

    private ResumePdf() {
    }

    /** Throws {@link IllegalArgumentException} with a sentence for the student if the file is not an acceptable resume PDF. */
    public static void check(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("the file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("the file is larger than 2 MB");
        }
        if (bytes.length < MAGIC.length + 20 || !startsWith(bytes)) {
            throw new IllegalArgumentException("only PDF files are accepted");
        }
        String tail = new String(bytes, Math.max(0, bytes.length - EOF_WINDOW), Math.min(bytes.length, EOF_WINDOW), StandardCharsets.ISO_8859_1);
        if (!tail.contains("%%EOF")) {
            throw new IllegalArgumentException("this PDF looks damaged or cut short");
        }
        // the names that matter are in the file's dictionaries; the binary bodies of its streams (images, fonts, compressed text) are skipped, where a short name could turn up by chance
        String structure = withoutStreamBodies(new String(bytes, StandardCharsets.ISO_8859_1));
        Matcher names = NAME.matcher(structure);
        while (names.find()) {
            String name = decode(names.group(1));
            if (FORBIDDEN.contains(name)) {
                throw new IllegalArgumentException("this PDF is " + ("Encrypt".equals(name) ? "password-protected" : "using features a resume must not (scripts, attachments or links to other files)")
                    + "; save a plain copy and upload that");
            }
        }
    }

    /** The content's SHA-256 as 64 lower-case hex digits: the file's name in storage and its identity everywhere. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The name to show for the file: the last part of what the sender gave, with anything but letters, digits, spaces and . _ - ( ) replaced, cut to 100 characters and ending
     * in .pdf. Never used as a path: files are stored under their hash.
     */
    public static String displayName(String sent) {
        String base = sent == null ? "" : sent.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).strip().replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
        if (base.toLowerCase().endsWith(".pdf")) {
            base = base.substring(0, base.length() - 4);
        }
        base = base.replaceAll("^[. ]+", "");
        if (base.length() > MAX_NAME - 4) {
            base = base.substring(0, MAX_NAME - 4);
        }
        return (base.isBlank() ? "resume" : base.strip()) + ".pdf";
    }

    private static boolean startsWith(byte[] bytes) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** The file's text with the bodies of its streams cut out, in one pass over it (a stream that is never closed makes the file damaged). */
    private static String withoutStreamBodies(String text) {
        StringBuilder out = new StringBuilder(text.length() / 4);
        int pos = 0;
        while (true) {
            int i = text.indexOf("stream", pos);
            if (i < 0) {
                out.append(text, pos, text.length());
                return out.toString();
            }
            int afterKeyword = i + "stream".length();
            boolean isEndKeyword = i >= 3 && text.startsWith("end", i - 3);
            int bodyStart = text.startsWith("\r\n", afterKeyword) ? afterKeyword + 2 : text.startsWith("\n", afterKeyword) ? afterKeyword + 1 : -1;
            if (isEndKeyword || bodyStart < 0) {
                out.append(text, pos, afterKeyword);
                pos = afterKeyword;
                continue;
            }
            int end = text.indexOf("endstream", bodyStart);
            if (end < 0) {
                throw new IllegalArgumentException("this PDF looks damaged or cut short");
            }
            out.append(text, pos, bodyStart);
            pos = end;
        }
    }

    /** A PDF name may spell any of its letters as #xx; decode that so a hidden spelling is the name it hides. */
    private static String decode(String raw) {
        if (raw.indexOf('#') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '#' && i + 2 < raw.length() && isHex(raw.charAt(i + 1)) && isHex(raw.charAt(i + 2))) {
                out.append((char) Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }
}
