package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.GlobalSearchResultDTO;
import com.wiki4ai.service.DocumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for SearchController (global cross-project search, WIKI4AI-61).
 * Tests the controller layer in isolation (security disabled — the login-only 401
 * behaviour is covered by SecurityConfigTest with security.enabled=true).
 */
@WebMvcTest(SearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DocumentService documentService;

    private final LocalDateTime now = LocalDateTime.of(2024, 5, 16, 10, 0);

    private GlobalSearchResultDTO sampleHit(long id, String projectSlug, String projectName) {
        return GlobalSearchResultDTO.builder()
                .id(id)
                .title("Doc " + id)
                .slug("doc-" + id)
                .projectId(1L)
                .projectSlug(projectSlug)
                .projectName(projectName)
                .score(0.032787)
                .updatedAt(now)
                .excerpt("…context around the keyword…")
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/search/documents - Global document search")
    class SearchDocumentsTests {

        @Test
        @DisplayName("Should return 200 with project attribution, score and excerpt per hit")
        void shouldReturnHitsWithProjectAttribution() throws Exception {
            given(documentService.searchDocumentsGlobal(eq("embedding"), anyString(), eq(20)))
                    .willReturn(List.of(
                            sampleHit(1L, "agent-helpers", "Agent Helpers"),
                            sampleHit(2L, "servers", "Servers")));

            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "embedding"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].projectSlug").value("agent-helpers"))
                    .andExpect(jsonPath("$[0].projectName").value("Agent Helpers"))
                    .andExpect(jsonPath("$[0].score").isNumber())
                    .andExpect(jsonPath("$[0].excerpt").value("…context around the keyword…"))
                    .andExpect(jsonPath("$[1].projectSlug").value("servers"));

            verify(documentService).searchDocumentsGlobal(eq("embedding"), anyString(), eq(20));
        }

        @Test
        @DisplayName("Should trim whitespace from keyword before searching")
        void shouldTrimKeyword() throws Exception {
            given(documentService.searchDocumentsGlobal(eq("Spring"), anyString(), eq(20)))
                    .willReturn(List.of(sampleHit(1L, "p", "P")));

            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "  Spring  "))
                    .andExpect(status().isOk());

            verify(documentService).searchDocumentsGlobal(eq("Spring"), anyString(), eq(20));
        }

        @Test
        @DisplayName("Should return 400 when keyword is missing")
        void shouldReturn400WhenKeywordMissing() throws Exception {
            mockMvc.perform(get("/api/v1/search/documents"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when keyword is shorter than 2 characters")
        void shouldReturn400WhenKeywordTooShort() throws Exception {
            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "x"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "   "))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should clamp the limit to [1, 50] and default to 20")
        void shouldClampLimit() throws Exception {
            given(documentService.searchDocumentsGlobal(anyString(), anyString(), anyInt()))
                    .willReturn(List.of());

            // default → 20
            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "test"))
                    .andExpect(status().isOk());
            verify(documentService).searchDocumentsGlobal(eq("test"), anyString(), eq(20));

            // above max → 50
            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "test").param("limit", "100"))
                    .andExpect(status().isOk());
            verify(documentService).searchDocumentsGlobal(eq("test"), anyString(), eq(50));

            // below min → 1
            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "test").param("limit", "0"))
                    .andExpect(status().isOk());
            verify(documentService).searchDocumentsGlobal(eq("test"), anyString(), eq(1));
        }

        @Test
        @DisplayName("Should return an empty JSON array when nothing matches")
        void shouldReturnEmptyArrayWhenNoMatch() throws Exception {
            given(documentService.searchDocumentsGlobal(eq("nonexistent"), anyString(), eq(20)))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/search/documents").param("keyword", "nonexistent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }
}
