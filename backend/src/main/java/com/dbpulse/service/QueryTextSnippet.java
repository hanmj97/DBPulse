package com.dbpulse.service;

/** On-demand bounded Query Store SQL text. Never persist or log queryText. */
public final class QueryTextSnippet {
    public static final int MAX_CHARACTERS = 65_536;

    private QueryTextSnippet() {}

    public static Result unavailable(long queryId, long planId) {
        return new Result(queryId, planId, "QUERY_TEXT_UNAVAILABLE", null, 0, false);
    }

    public static Result from(long queryId, long planId, String text, long originalBytes) {
        if (text == null || text.isEmpty()) return unavailable(queryId, planId);
        long originalCharacters = Math.max(0L, originalBytes / 2L); // SQL Server NVARCHAR UTF-16 units
        boolean truncated = originalCharacters > MAX_CHARACTERS || text.length() > MAX_CHARACTERS;
        String shown = text.length() > MAX_CHARACTERS
            ? text.substring(0, MAX_CHARACTERS) : text;
        // Do not leave a broken surrogate at the truncation boundary.
        if (truncated && !shown.isEmpty()
            && Character.isHighSurrogate(shown.charAt(shown.length() - 1))) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return new Result(queryId, planId, "AVAILABLE", shown,
            originalCharacters, truncated);
    }

    public record Result(long queryId, long planId, String status,
                         String queryText, long originalCharacters, boolean truncated) {}
}
