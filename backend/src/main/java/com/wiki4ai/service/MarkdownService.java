package com.wiki4ai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for markdown parsing and wiki link processing.
 * Handles conversion of markdown content to HTML, extraction of wiki-style links [[Document]],
 * and replacement of those links with proper HTML anchor tags.
 */
@Service
public class MarkdownService {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^]]+)\\]\\]");

    private final com.vladsch.flexmark.parser.Parser markdownParser;
    private final com.vladsch.flexmark.html.HtmlRenderer htmlRenderer;

    public MarkdownService() {
        com.vladsch.flexmark.util.data.MutableDataSet options = new com.vladsch.flexmark.util.data.MutableDataSet();

        this.markdownParser = com.vladsch.flexmark.parser.Parser.builder(options).build();
        this.htmlRenderer = com.vladsch.flexmark.html.HtmlRenderer.builder(options).build();
    }

    /**
     * Converts markdown content to HTML with wiki links replaced by proper anchor tags.
     * This is a convenience method that combines render() and replaceWikiLinks().
     * Wiki-style links [[Document]] are converted to <a href="/projects/{projectSlug}/docs/{docSlug}">Document</a>.
     *
     * @param markdownContent the raw markdown string
     * @param projectSlug the project slug for URL generation
     * @param docSlugMap a map of document titles (case-insensitive) to their slugs
     * @return the rendered HTML with wiki links replaced by anchor tags
     */
    public String renderWithWikiLinks(String markdownContent, String projectSlug, Map<String, String> docSlugMap) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return "";
        }

        // Build slug-to-URL map from the document slugs
        Map<String, String> slugToUrl = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : docSlugMap.entrySet()) {
            String url = "/projects/" + projectSlug + "/docs/" + entry.getValue();
            // Store with lowercase key for case-insensitive lookup
            slugToUrl.put(entry.getKey().toLowerCase(), url);
        }

        // First render markdown to HTML (preserving wiki links as [[...]])
        String html = render(markdownContent);

        // Then replace wiki links with proper anchor tags
        return replaceWikiLinks(html, slugToUrl);
    }

    /**
     * Converts markdown content to HTML.
     * Supports standard markdown features including headings, paragraphs, lists, code blocks,
     * bold/italic text, links, and blockquotes.
     * Wiki-style links [[Document]] are preserved as-is for later replacement via replaceWikiLinks().
     *
     * @param markdownContent the raw markdown string
     * @return the rendered HTML string
     */
    public String render(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return "";
        }

        // Extract wiki link titles first
        List<String> wikiLinkTitles = extractWikiLinks(markdownContent);

        // Replace all wiki links with unique numbered placeholders to prevent flexmark from processing them.
        // Use HTML comments which pass through the renderer unchanged.
        String contentWithPlaceholders = markdownContent;
        for (int i = 0; i < wikiLinkTitles.size(); i++) {
            // Use an HTML comment placeholder that won't be affected by markdown rendering
            String placeholder = "<!--WIKILINK_" + i + "_WIKILINK-->";
            // Escape special regex characters in the title for safe replacement
            String escapedTitle = Pattern.quote("[[" + wikiLinkTitles.get(i) + "]]");
            contentWithPlaceholders = contentWithPlaceholders.replaceFirst(escapedTitle, placeholder);
        }

        // Render markdown to HTML using flexmark
        String html = htmlRenderer.render(markdownParser.parse(contentWithPlaceholders));

        // Restore wiki links as [[...]] placeholders (they will be replaced later via replaceWikiLinks)
        for (int i = 0; i < wikiLinkTitles.size(); i++) {
            String placeholder = "<!--WIKILINK_" + i + "_WIKILINK-->";
            html = html.replace(placeholder, "[[" + wikiLinkTitles.get(i) + "]]");
        }

        return html;
    }

    /**
     * Extracts all wiki-style links from markdown content.
     * Wiki links follow the pattern [[Document Name]].
     * Returns a list of unique document titles found in the content.
     *
     * @param markdownContent the raw markdown string
     * @return a list of unique wiki link titles (e.g., ["Introduction", "API Reference"])
     */
    public List<String> extractWikiLinks(String markdownContent) {
        Set<String> uniqueTitles = new HashSet<>();

        if (markdownContent == null || markdownContent.isBlank()) {
            return new ArrayList<>(uniqueTitles);
        }

        Matcher matcher = WIKI_LINK_PATTERN.matcher(markdownContent);
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            if (!title.isEmpty()) {
                uniqueTitles.add(title);
            }
        }

        return new ArrayList<>(uniqueTitles);
    }

    /**
     * Replaces wiki-style links [[Document]] with HTML anchor tags.
     * Uses the slugToUrlMap to resolve document titles to their URLs.
     * If a title is not found in the map, it falls back to using the title as-is for URL generation.
     *
     * Example:
     *   Input:  "See [[Introduction]] and [[API Reference]] for details."
     *   Map:    {"introduction" -> "/projects/1/docs/introduction", "api-reference" -> "/projects/1/docs/api-reference"}
     *   Output: "See <a href=\"/projects/1/docs/introduction\">Introduction</a> and <a href=\"/projects/1/docs/api-reference\">API Reference</a> for details."
     *
     * @param html            the HTML content containing [[WikiLink]] placeholders
     * @param slugToUrlMap    a map of document slugs to their URLs
     * @return the HTML with wiki links replaced by anchor tags
     */
    public String replaceWikiLinks(String html, Map<String, String> slugToUrlMap) {
        if (html == null || html.isBlank()) {
            return "";
        }

        Matcher matcher = WIKI_LINK_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            String slug = generateSlug(title);

            // Look up the URL from the map, using slug as key (case-insensitive)
            String url = null;
            if (slugToUrlMap != null) {
                for (Map.Entry<String, String> entry : slugToUrlMap.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(slug)) {
                        url = entry.getValue();
                        break;
                    }
                }
            }

            // Fallback: generate URL from title if not found in map
            if (url == null) {
                url = "/" + slug;
            }

            String replacement = "<a href=\"" + escapeHtml(url) + "\">" + escapeHtml(title) + "</a>";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Generates a URL-friendly slug from a title.
     * Same logic as Document.generateSlug() for consistency.
     * Transliterates diacritics (e.g., "ú" → "u") then strips remaining non-ASCII chars.
     *
     * @param title the document title
     * @return a URL-friendly slug (e.g., "My Document" → "my-document", "Úvod" → "uvod")
     */
    public static String generateSlug(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String slug = title.toLowerCase();
        // Transliterate diacritics to ASCII equivalents
        slug = slug.replaceAll("[áäàâ]", "a")
                .replaceAll("č", "c")
                .replaceAll("[ďđ]", "d")
                .replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i")
                .replaceAll("[ĺľ]", "l")
                .replaceAll("ň", "n")
                .replaceAll("[óòôöõ]", "o")
                .replaceAll("ŕ", "r")
                .replaceAll("š", "s")
                .replaceAll("ť", "t")
                .replaceAll("[úùûü]", "u")
                .replaceAll("[ýỳÿ]", "y")
                .replaceAll("ž", "z");
        // Strip remaining non-ASCII, normalize spaces and dashes
        return slug.replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .trim()
                .replaceAll("^-|-$", "");
    }

    /**
     * Escapes HTML special characters in a string for safe output.
     */
    private String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
