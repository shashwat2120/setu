package com.setu.learning.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * What a lesson's {@code content_ref} or a resource's {@code media_ref} may point at. These strings are rendered as links (and a YouTube
 * address as an embedded player), so anything that is not a plain, safe address is refused at the door rather than escaped at every
 * place it is shown:
 * <ul>
 *   <li>{@code https://host/...} — a real web address (a YouTube video, a PDF, a documentation page). Plain {@code http://} is refused.</li>
 *   <li>{@code ref://...} and {@code s3://...} — the internal object-storage style references this system already uses.</li>
 * </ul>
 * Everything else ({@code javascript:}, {@code data:}, {@code file:}, {@code ftp:}, relative paths) is rejected, as is anything with
 * whitespace or control characters, credentials in the address, a missing host, or more than {@value #MAX_LENGTH} characters.
 */
public final class ContentReferences {

    public static final int MAX_LENGTH = 2048;
    public static final int MAX_TITLE_LENGTH = 200;

    private static final Set<String> INTERNAL_SCHEMES = Set.of("ref", "s3");

    private ContentReferences() {
    }

    /** A lesson's own reference is optional; when present it must be acceptable. Returns the trimmed value, or null. */
    public static String optional(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return required(ref);
    }

    /** A reference that must be present and acceptable. Returns the trimmed value. */
    public static String required(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("a reference is required");
        }
        String value = ref.trim();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("a reference must be at most " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException("a reference must not contain spaces or control characters");
            }
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (INTERNAL_SCHEMES.contains(scheme)) {
            return value;
        }
        if (!scheme.equals("https")) {
            throw new IllegalArgumentException("a link must start with https:// (other schemes are not allowed)");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("the link has no host name");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("a link must not contain a user name or password");
        }
        return value;
    }

    /** A human-readable name for a resource: optional, trimmed, at most {@value #MAX_TITLE_LENGTH} characters; blank becomes null. */
    public static String title(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String value = title.trim();
        if (value.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("a title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        return value;
    }
}
