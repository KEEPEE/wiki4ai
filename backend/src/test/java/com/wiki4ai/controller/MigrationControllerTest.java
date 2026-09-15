package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.ImportResultDTO;
import com.wiki4ai.dto.MigrationExportDTO;
import com.wiki4ai.service.MigrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for {@link MigrationController} (WIKI4AI-41): endpoint wiring and
 * the auth/authorization error mapping (401 vs 403).
 */
@WebMvcTest(MigrationController.class)
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class MigrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MigrationService migrationService;

    private MigrationExportDTO sampleArchive() {
        return new MigrationExportDTO(
                MigrationExportDTO.CURRENT_FORMAT_VERSION,
                "2026-09-15T12:00:00Z",
                List.of("alice"),
                List.of(new MigrationExportDTO.ProjectEntry(1L, "Alpha", "alpha", null, null, null, null)),
                List.of(),
                List.of()
        );
    }

    private ImportResultDTO sampleResult() {
        Map<Long, Long> emptyIds = new LinkedHashMap<>();
        Map<String, Long> emptyUsers = new LinkedHashMap<>();
        return new ImportResultDTO(
                "merge", "2026-09-15T12:00:00Z",
                new ImportResultDTO.Counts(0, 0, 0),
                new ImportResultDTO.Counts(1, 0, 0),
                1, 0, 0, 0, 0, 0, 0, 0,
                emptyIds, emptyIds, emptyUsers, List.of(),
                0, List.of(), true, "ok", false, List.of()
        );
    }

    @Test
    @DisplayName("GET /api/v1/migration/export returns the archive JSON")
    void exportReturnsArchive() throws Exception {
        given(migrationService.export()).willReturn(sampleArchive());

        mockMvc.perform(get("/api/v1/migration/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formatVersion").value(1))
                .andExpect(jsonPath("$.projects[0].slug").value("alpha"))
                .andExpect(jsonPath("$.users[0]").value("alice"));
    }

    @Test
    @DisplayName("POST /api/v1/migration/import (merge) returns the result with ID mapping and integrity counts")
    void importMergeReturnsResult() throws Exception {
        given(migrationService.importData(any(MigrationExportDTO.class), eq("merge"), isNull(), any()))
                .willReturn(sampleResult());

        mockMvc.perform(post("/api/v1/migration/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleArchive())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("merge"))
                .andExpect(jsonPath("$.projectsCreated").value(1))
                .andExpect(jsonPath("$.noDataLoss").value(true))
                .andExpect(jsonPath("$.before.projects").value(0))
                .andExpect(jsonPath("$.after.projects").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/migration/import passes mode and confirmFullRestore through")
    void importFullPassesParams() throws Exception {
        given(migrationService.importData(any(MigrationExportDTO.class), eq("full"), eq(true), any()))
                .willReturn(sampleResult());

        mockMvc.perform(post("/api/v1/migration/import")
                        .param("mode", "full")
                        .param("confirmFullRestore", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleArchive())))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(migrationService)
                .importData(any(MigrationExportDTO.class), eq("full"), eq(true), any());
    }

    @Test
    @DisplayName("import without authentication → 401")
    void importUnauthenticatedIs401() throws Exception {
        doThrow(new SecurityException("Authentication required. Please provide a valid JWT token."))
                .when(migrationService).verifyAdminRole(any());

        mockMvc.perform(post("/api/v1/migration/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleArchive())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("import by non-admin → 403")
    void importNonAdminIs403() throws Exception {
        doThrow(new SecurityException("Admin access required. Current role: USER"))
                .when(migrationService).verifyAdminRole(any());

        mockMvc.perform(post("/api/v1/migration/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleArchive())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }
}
