package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class QueryTextSnippetTest {
    @Test
    void returnsExactTextWithoutNormalizationOrHtmlExecution() {
        String original = "SELECT * FROM dbo.DeadlockDemo WHERE name = N'<script>'";
        var result = QueryTextSnippet.from(247, 191, original, original.length() * 2L);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.queryText()).isEqualTo(original);
        assertThat(result.truncated()).isFalse();
        assertThat(result.queryId()).isEqualTo(247L);
        assertThat(result.planId()).isEqualTo(191L);
    }

    @Test
    void capsLongSqlAndReportsTruncation() {
        String original = "X".repeat(QueryTextSnippet.MAX_CHARACTERS + 900);
        var result = QueryTextSnippet.from(15, 16,
            original.substring(0, QueryTextSnippet.MAX_CHARACTERS + 1),
            original.length() * 2L);
        assertThat(result.queryText()).hasSize(QueryTextSnippet.MAX_CHARACTERS);
        assertThat(result.truncated()).isTrue();
        assertThat(result.originalCharacters()).isEqualTo(original.length());
    }

    @Test
    void retainsNewlinesWhitespaceAndDoesNotAutoRedactLiterals() {
        String sql = "SELECT *\nFROM t\nWHERE token = 'sample-literal'   ";
        var result = QueryTextSnippet.from(3, 4, sql, sql.length() * 2L);
        assertThat(result.queryText()).isEqualTo(sql);
    }

    @Test
    void preservesValidUnicodeBoundaryWhenTruncating() {
        String text = "A".repeat(QueryTextSnippet.MAX_CHARACTERS - 1) + "😀" + "END";
        var result = QueryTextSnippet.from(1, 2,
            text.substring(0, QueryTextSnippet.MAX_CHARACTERS + 1),
            text.length() * 2L);
        assertThat(result.truncated()).isTrue();
        assertThat(result.queryText()).hasSize(QueryTextSnippet.MAX_CHARACTERS - 1);
    }

    @Test
    void unavailableTextDoesNotInventSqlStatement() {
        assertThat(QueryTextSnippet.unavailable(10, 20).queryText()).isNull();
        assertThat(QueryTextSnippet.from(10, 20, null, 0).status())
            .isEqualTo("QUERY_TEXT_UNAVAILABLE");
        assertThat(QueryTextSnippet.from(10, 20, "", 0).status())
            .isEqualTo("QUERY_TEXT_UNAVAILABLE");
    }
}
