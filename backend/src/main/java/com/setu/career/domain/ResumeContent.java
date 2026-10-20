package com.setu.career.domain;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * What a student says about themselves on a resume: a headline and a summary, links to their profiles, and the projects, certifications and experience they list. The
 * student's skills, grades and attendance are not here: they come from the system's own records, so a resume cannot claim them. Every field is optional but a resume is
 * only {@linkplain #complete() complete} with a headline.
 *
 * <p>Everything a student types is bounded, and a link must be an https address: it is shown to the placement officer as a link, so anything else (a script address, a file
 * path) is refused rather than escaped later.
 */
public record ResumeContent(String headline, String summary, String linkedinUrl, String githubUrl, String portfolioUrl, List<Item> items) {

    public static final int MAX_HEADLINE = 120;
    public static final int MAX_SUMMARY = 1000;
    public static final int MAX_URL = 300;
    public static final int MAX_ITEMS = 20;
    public static final int MAX_TITLE = 120;
    public static final int MAX_ORGANISATION = 120;
    public static final int MAX_DESCRIPTION = 600;
    public static final Set<String> KINDS = Set.of("PROJECT", "CERTIFICATION", "EXPERIENCE");

    public static final ResumeContent EMPTY = new ResumeContent(null, null, null, null, null, List.of());

    /** One project, certification or piece of experience. {@code endDate} null means it is ongoing. */
    public record Item(String kind, String title, String organisation, String description, LocalDate startDate, LocalDate endDate) {
        public Item {
            title = blankToNull(title);
            organisation = blankToNull(organisation);
            description = blankToNull(description);
            kind = kind == null ? null : kind.strip().toUpperCase();
        }
    }

    public ResumeContent {
        headline = blankToNull(headline);
        summary = blankToNull(summary);
        linkedinUrl = blankToNull(linkedinUrl);
        githubUrl = blankToNull(githubUrl);
        portfolioUrl = blankToNull(portfolioUrl);
        items = items == null ? List.of() : List.copyOf(items);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A resume is complete when it has a headline: that is the least a placement officer needs to know who they are looking at. */
    public boolean complete() {
        return headline != null;
    }

    /** Throws {@link IllegalArgumentException} naming the first value that is too long, malformed or makes no sense. */
    public void validate() {
        limit("the headline", headline, MAX_HEADLINE);
        limit("the summary", summary, MAX_SUMMARY);
        link("the LinkedIn address", linkedinUrl);
        link("the GitHub address", githubUrl);
        link("the portfolio address", portfolioUrl);
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a resume can list at most " + MAX_ITEMS + " projects, certifications and experiences in all");
        }
        for (Item item : items) {
            if (item == null || item.kind() == null || !KINDS.contains(item.kind())) {
                throw new IllegalArgumentException("each entry must be a project, a certification or an experience");
            }
            if (item.title() == null) {
                throw new IllegalArgumentException("each entry needs a title");
            }
            limit("a title", item.title(), MAX_TITLE);
            limit("an organisation", item.organisation(), MAX_ORGANISATION);
            limit("a description", item.description(), MAX_DESCRIPTION);
            for (LocalDate date : new LocalDate[] {item.startDate(), item.endDate()}) {
                if (date != null && (date.getYear() < 1970 || date.getYear() > 2100)) {
                    throw new IllegalArgumentException("a date on the resume is not a plausible one");
                }
            }
            if (item.startDate() != null && item.endDate() != null && item.endDate().isBefore(item.startDate())) {
                throw new IllegalArgumentException("an entry cannot end before it starts");
            }
        }
    }

    private static void limit(String what, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(what + " must be at most " + max + " characters");
        }
    }

    private static void link(String what, String value) {
        if (value == null) {
            return;
        }
        limit(what, value, MAX_URL);
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || value.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(what + " must be an https link");
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(what + " must be an https link");
        }
    }
}
