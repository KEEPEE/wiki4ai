package com.wiki4ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MarkdownService.
 * Tests markdown rendering, wiki link extraction, and wiki link replacement.
 */
class MarkdownServiceTest {

    private MarkdownService markdownService;

    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService();
    }

    // ==================== render() tests ====================

    @Nested
    @DisplayName("render - markdown to HTML conversion")
    class RenderTests {

        @Test
        @DisplayName("Should convert headings to HTML")
        void shouldConvertHeadingsToHtml() {
            // given
            String markdown = "# Heading 1\n## Heading 2\n### Heading 3";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<h1>Heading 1</h1>");
            assertThat(html).contains("<h2>Heading 2</h2>");
            assertThat(html).contains("<h3>Heading 3</h3>");
        }

        @Test
        @DisplayName("Should convert paragraphs to HTML")
        void shouldConvertParagraphsToHtml() {
            // given
            String markdown = "This is a paragraph.\n\nThis is another paragraph.";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<p>This is a paragraph.</p>");
            assertThat(html).contains("<p>This is another paragraph.</p>");
        }

        @Test
        @DisplayName("Should convert unordered lists to HTML")
        void shouldConvertUnorderedListsToHtml() {
            // given
            String markdown = "- Item 1\n- Item 2\n- Item 3";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<ul>");
            assertThat(html).contains("<li>Item 1</li>");
            assertThat(html).contains("<li>Item 2</li>");
        }

        @Test
        @DisplayName("Should convert ordered lists to HTML")
        void shouldConvertOrderedListsToHtml() {
            // given
            String markdown = "1. First\n2. Second\n3. Third";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<ol>");
            assertThat(html).contains("<li>First</li>");
            assertThat(html).contains("<li>Second</li>");
        }

        @Test
        @DisplayName("Should convert code blocks to HTML")
        void shouldConvertCodeBlocksToHtml() {
            // given
            String markdown = "Here is some `inline code`.\n\n```\ncode block\n```";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<code>");
        }

        @Test
        @DisplayName("Should convert bold and italic text to HTML")
        void shouldConvertBoldAndItalicToHtml() {
            // given
            String markdown = "**bold** and *italic*";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<strong>bold</strong>");
            assertThat(html).contains("<em>italic</em>");
        }

        @Test
        @DisplayName("Should convert blockquotes to HTML")
        void shouldConvertBlockquotesToHtml() {
            // given
            String markdown = "> This is a quote";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<blockquote>");
            assertThat(html).contains("This is a quote");
        }

        @Test
        @DisplayName("Should convert links to HTML")
        void shouldConvertLinksToHtml() {
            // given
            String markdown = "[Click here](https://example.com)";

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<a href=\"https://example.com\">Click here</a>");
        }

        @Test
        @DisplayName("Should handle empty string")
        void shouldHandleEmptyString() {
            // when
            String html = markdownService.render("");

            // then
            assertThat(html).isEmpty();
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            // when
            String html = markdownService.render(null);

            // then
            assertThat(html).isEmpty();
        }

        @Test
        @DisplayName("Should preserve wiki links as-is during render (not processed by flexmark)")
        void shouldPreserveWikiLinksDuringRender() {
            // given
            String markdown = "See [[Introduction]] for details.";

            // when
            String html = markdownService.render(markdown);

            // then — wiki links should remain as [[...]] placeholders
            assertThat(html).contains("[[Introduction]]");
        }

        @Test
        @DisplayName("Should handle complex mixed markdown content")
        void shouldHandleComplexMixedMarkdown() {
            // given
            String markdown = """
                    # Welcome to Wiki4AI

                    This is a **bold** statement with *italic* text.

                    ## Features

                    - Markdown parsing
                    - Wiki links: [[Getting Started]] and [[API Reference]]
                    - Code blocks

                    ### Example

                    `inline code` and

                    ```
                    function hello() {
                        console.log("world");
                    }
                    ```
                    """;

            // when
            String html = markdownService.render(markdown);

            // then
            assertThat(html).contains("<h1>Welcome to Wiki4AI</h1>");
            assertThat(html).contains("<strong>bold</strong>");
            assertThat(html).contains("<em>italic</em>");
            assertThat(html).contains("<ul>");
            assertThat(html).contains("[[Getting Started]]");
            assertThat(html).contains("[[API Reference]]");
            assertThat(html).contains("<code>");
        }

        @Test
        @DisplayName("Should handle multiple wiki links with same title")
        void shouldHandleDuplicateWikiLinks() {
            // given
            String markdown = "See [[Introduction]] and then [[Introduction]] again.";

            // when
            String html = markdownService.render(markdown);

            // then — both occurrences should be preserved as [[...]]
            assertThat(html).contains("[[Introduction]]");
        }
    }

    // ==================== extractWikiLinks() tests ====================

    @Nested
    @DisplayName("extractWikiLinks - wiki link extraction")
    class ExtractWikiLinksTests {

        @Test
        @DisplayName("Should extract a single wiki link")
        void shouldExtractSingleWikiLink() {
            // given
            String markdown = "See [[Introduction]] for more details.";

            // when
            List<String> links = markdownService.extractWikiLinks(markdown);

            // then
            assertThat(links).containsExactly("Introduction");
        }

        @Test
        @DisplayName("Should extract multiple wiki links")
        void shouldExtractMultipleWikiLinks() {
            // given
            String markdown = "Check [[Getting Started]] and [[API Reference]] for details.";

            // when
            List<String> links = markdownService.extractWikiLinks(markdown);

            // then
            assertThat(links).containsExactlyInAnyOrder("Getting Started", "API Reference");
        }

        @Test
        @DisplayName("Should return unique titles only (deduplicate)")
        void shouldDeduplicateWikiLinks() {
            // given
            String markdown = "See [[Introduction]] and then [[Introduction]] again.";

            // when
            List<String> links = markdownService.extractWikiLinks(markdown);

            // then
            assertThat(links).containsExactly("Introduction");
        }

        @Test
        @DisplayName("Should return empty list for content without wiki links")
        void shouldReturnEmptyListWhenNoWikiLinks() {
            // given
            String markdown = "This is plain text with no wiki links.";

            // when
            List<String> links = markdownService.extractWikiLinks(markdown);

            // then
            assertThat(links).isEmpty();
        }

        @Test
        @DisplayName("Should handle empty string")
        void shouldHandleEmptyString() {
            // when
            List<String> links = markdownService.extractWikiLinks("");

            // then
            assertThat(links).isEmpty();
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            // when
            List<String> links = markdownService.extractWikiLinks(null);

            // then
            assertThat(links).isEmpty();
        }

        @Test
        @DisplayName("Should trim whitespace from wiki link titles")
        void shouldTrimWhitespaceFromTitles() {
            // given
            String markdown = "See [[  Introduction  ]] for details.";

            // when
            List<String> links = markdownService.extractWikiLinks(markdown);

            // then
            assertThat(links).containsExactly("Introduction");
        }

        @Test
        @DisplayName("Should ignore empty wiki link brackets")
        void shouldIgnoreEmptyBrackets() {
            // given
            String markdown = "See [] and [[]] for details.";

            // when
            List<String> links = markdownService.extractWikiLinks(markdown);

            // then
            assertThat(links).isEmpty();
        }

        @Test
        @DisplayName("Should handle wiki links mixed with other markdown")
        void shouldHandleWikiLinksMixedWithMarkdown() {
            // given
            String markdown = """
                    # Welcome

                    This is **bold** text with [[Getting Started]] link.

                    - Item 1: [[API Reference]]
                    - Item 2: [[FAQ]]
                    """;

            // when
            List<String> links = markdownService.extractWikiLinks(markdown);

            // then
            assertThat(links).containsExactlyInAnyOrder("Getting Started", "API Reference", "FAQ");
        }
    }

    // ==================== replaceWikiLinks() tests ====================

    @Nested
    @DisplayName("replaceWikiLinks - wiki link replacement with HTML anchors")
    class ReplaceWikiLinksTests {

        private Map<String, String> slugToUrlMap;

        @BeforeEach
        void setUpSlugToUrlMap() {
            slugToUrlMap = new HashMap<>();
            slugToUrlMap.put("introduction", "/projects/1/docs/introduction");
            slugToUrlMap.put("api-reference", "/projects/1/docs/api-reference");
            slugToUrlMap.put("getting-started", "/projects/1/docs/getting-started");
        }

        @Test
        @DisplayName("Should replace a single wiki link with an anchor tag")
        void shouldReplaceSingleWikiLink() {
            // given
            String html = "See [[Introduction]] for more details.";

            // when
            String result = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then
            assertThat(result).contains("<a href=\"/projects/1/docs/introduction\">Introduction</a>");
        }

        @Test
        @DisplayName("Should replace multiple wiki links with anchor tags")
        void shouldReplaceMultipleWikiLinks() {
            // given
            String html = "Check [[Getting Started]] and [[API Reference]] for details.";

            // when
            String result = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then
            assertThat(result).contains("<a href=\"/projects/1/docs/getting-started\">Getting Started</a>");
            assertThat(result).contains("<a href=\"/projects/1/docs/api-reference\">API Reference</a>");
        }

        @Test
        @DisplayName("Should fallback to slug-based URL when title not found in map")
        void shouldFallbackToSlugBasedUrl() {
            // given
            String html = "See [[Unknown Document]] for details.";

            // when
            String result = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then
            assertThat(result).contains("<a href=\"/unknown-document\">Unknown Document</a>");
        }

        @Test
        @DisplayName("Should handle null map by using fallback URLs")
        void shouldHandleNullMap() {
            // given
            String html = "See [[Introduction]] for details.";

            // when
            String result = markdownService.replaceWikiLinks(html, null);

            // then
            assertThat(result).contains("<a href=\"/introduction\">Introduction</a>");
        }

        @Test
        @DisplayName("Should handle empty string")
        void shouldHandleEmptyString() {
            // when
            String result = markdownService.replaceWikiLinks("", slugToUrlMap);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            // when
            String result = markdownService.replaceWikiLinks(null, slugToUrlMap);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should escape special characters in URLs and titles")
        void shouldEscapeSpecialCharacters() {
            // given
            Map<String, String> mapWithSpecialChars = new HashMap<>();
            mapWithSpecialChars.put("test", "/path?foo=bar&baz=qux");

            String html = "See [[Test]] for details.";

            // when
            String result = markdownService.replaceWikiLinks(html, mapWithSpecialChars);

            // then
            assertThat(result).contains("&amp;");
            assertThat(result).doesNotContain("<a href=\"/path?foo=bar&baz=qux\">");
        }

        @Test
        @DisplayName("Should handle wiki links in complex HTML content")
        void shouldHandleWikiLinksInComplexHtml() {
            // given
            String html = """
                    <h1>Welcome</h1>
                    <p>This is a paragraph with [[Introduction]] link.</p>
                    <ul>
                        <li><a href="/other">Other</a></li>
                        <li>See [[API Reference]] for more.</li>
                    </ul>
                    """;

            // when
            String result = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then
            assertThat(result).contains("<a href=\"/projects/1/docs/introduction\">Introduction</a>");
            assertThat(result).contains("<a href=\"/projects/1/docs/api-reference\">API Reference</a>");
        }

        @Test
        @DisplayName("Should preserve existing HTML tags while replacing wiki links")
        void shouldPreserveExistingHtmlTags() {
            // given
            String html = "<div><p>Check [[Getting Started]] for help.</p></div>";

            // when
            String result = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then
            assertThat(result).contains("<div>");
            assertThat(result).contains("<p>");
            assertThat(result).contains("</p>");
            assertThat(result).contains("</div>");
            assertThat(result).contains("<a href=\"/projects/1/docs/getting-started\">Getting Started</a>");
        }

        @Test
        @DisplayName("Should handle case-insensitive slug matching")
        void shouldHandleCaseInsensitiveSlugMatching() {
            // given — map has lowercase slug, wiki link has mixed case
            String html = "See [[INTRODUCTION]] for details.";

            // when
            String result = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then — URL from map should be used (case-insensitive match)
            assertThat(result).contains("<a href=\"/projects/1/docs/introduction\">INTRODUCTION</a>");
        }

        @Test
        @DisplayName("Should replace all occurrences of the same wiki link")
        void shouldReplaceAllOccurrencesOfSameLink() {
            // given
            String html = "See [[Introduction]] and then [[Introduction]] again.";

            // when
            String result = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then — both occurrences should be replaced
            assertThat(result).contains("<a href=\"/projects/1/docs/introduction\">Introduction</a>");
            assertThat(result).doesNotContain("[[");
        }
    }

    // ==================== generateSlug() tests ====================

    @Nested
    @DisplayName("generateSlug - slug generation")
    class GenerateSlugTests {

        @Test
        @DisplayName("Should convert simple title to slug")
        void shouldConvertSimpleTitle() {
            assertThat(MarkdownService.generateSlug("My Document")).isEqualTo("my-document");
        }

        @Test
        @DisplayName("Should handle titles with special characters")
        void shouldHandleSpecialCharacters() {
            assertThat(MarkdownService.generateSlug("Hello World!")).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("Should handle titles with multiple spaces")
        void shouldHandleMultipleSpaces() {
            assertThat(MarkdownService.generateSlug("  Multiple   Spaces  ")).isEqualTo("multiple-spaces");
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            assertThat(MarkdownService.generateSlug(null)).isEmpty();
        }

        @Test
        @DisplayName("Should handle empty string")
        void shouldHandleEmptyString() {
            assertThat(MarkdownService.generateSlug("")).isEmpty();
        }

        @Test
        @DisplayName("Should handle Slovak characters (remove non-ASCII)")
        void shouldHandleSlovakCharacters() {
            String slug = MarkdownService.generateSlug("Ahoj Svet");
            assertThat(slug).isEqualTo("ahoj-svet");
        }
    }

    // ==================== Integration: render + extract + replace ====================

    @Nested
    @DisplayName("Integration - full markdown processing pipeline")
    class FullPipelineTests {

        private Map<String, String> slugToUrlMap;

        @BeforeEach
        void setUpSlugToUrlMap() {
            slugToUrlMap = new HashMap<>();
            slugToUrlMap.put("introduction", "/projects/1/docs/introduction");
            slugToUrlMap.put("api-reference", "/projects/1/docs/api-reference");
            slugToUrlMap.put("getting-started", "/projects/1/docs/getting-started");
        }

        @Test
        @DisplayName("Should render markdown and replace wiki links in one flow")
        void shouldRenderAndReplaceWikiLinks() {
            // given
            String markdown = """
                    # Welcome to Wiki4AI

                    This is the **introduction** page. See [[Getting Started]] for setup instructions.
                    For technical details, check [[API Reference]].
                    """;

            // when — step 1: extract wiki links
            List<String> wikiLinks = markdownService.extractWikiLinks(markdown);

            // then — verify extraction
            assertThat(wikiLinks).containsExactlyInAnyOrder("Getting Started", "API Reference");

            // when — step 2: render to HTML
            String html = markdownService.render(markdown);

            // then — verify rendering preserves wiki links as placeholders
            assertThat(html).contains("<h1>Welcome to Wiki4AI</h1>");
            assertThat(html).contains("<strong>introduction</strong>");
            assertThat(html).contains("[[Getting Started]]");
            assertThat(html).contains("[[API Reference]]");

            // when — step 3: replace wiki links with anchor tags
            String finalHtml = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then — verify all wiki links are replaced
            assertThat(finalHtml).contains("<a href=\"/projects/1/docs/getting-started\">Getting Started</a>");
            assertThat(finalHtml).contains("<a href=\"/projects/1/docs/api-reference\">API Reference</a>");
            assertThat(finalHtml).doesNotContain("[[");
        }

        @Test
        @DisplayName("Should handle document with no wiki links")
        void shouldHandleDocumentWithoutWikiLinks() {
            // given
            String markdown = "# Plain Document\n\nThis has **no** wiki links.";

            // when
            List<String> wikiLinks = markdownService.extractWikiLinks(markdown);
            String html = markdownService.render(markdown);
            String finalHtml = markdownService.replaceWikiLinks(html, slugToUrlMap);

            // then
            assertThat(wikiLinks).isEmpty();
            assertThat(finalHtml).contains("<h1>Plain Document</h1>");
            assertThat(finalHtml).doesNotContain("[[");
        }
    }
}
