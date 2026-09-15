package com.wiki4ai.controller;

import com.wiki4ai.service.EmbeddingService;
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

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for {@link EmbeddingAdminController} (backfill + status).
 */
@WebMvcTest(EmbeddingAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class EmbeddingAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingService embeddingService;

    @Test
    @DisplayName("POST /backfill as admin starts the backfill (202)")
    void startBackfillAsAdmin() throws Exception {
        when(embeddingService.startBackfill(anyBoolean())).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/embeddings/backfill"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("started"));
    }

    @Test
    @DisplayName("POST /backfill?force=true passes force through")
    void startBackfillForce() throws Exception {
        when(embeddingService.startBackfill(true)).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/embeddings/backfill").param("force", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.force").value(true));
    }

    @Test
    @DisplayName("POST /backfill returns 409 when a backfill is already running")
    void startBackfillConflict() throws Exception {
        when(embeddingService.startBackfill(anyBoolean())).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/embeddings/backfill"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A backfill is already running"));
    }

    @Test
    @DisplayName("POST /backfill returns 403 for non-admin users")
    void startBackfillForbidden() throws Exception {
        doThrow(new SecurityException("Admin access required. Current role: USER"))
                .when(embeddingService).verifyAdminRole();

        mockMvc.perform(post("/api/v1/admin/embeddings/backfill"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Admin access required. Current role: USER"));
    }

    @Test
    @DisplayName("POST /backfill returns 401 for unauthenticated callers")
    void startBackfillUnauthorized() throws Exception {
        doThrow(new SecurityException("Authentication required. Please provide a valid JWT token."))
                .when(embeddingService).verifyAdminRole();

        mockMvc.perform(post("/api/v1/admin/embeddings/backfill"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /backfill/status returns the live status (200)")
    void backfillStatus() throws Exception {
        when(embeddingService.getStatus()).thenReturn(new EmbeddingService.BackfillStatus(
                true, false, 301, 150, 150, 0, 0, null, Instant.now(), null));

        mockMvc.perform(get("/api/v1/admin/embeddings/backfill/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.total").value(301))
                .andExpect(jsonPath("$.processed").value(150))
                .andExpect(jsonPath("$.embedded").value(150));
    }

    @Test
    @DisplayName("GET /backfill/status returns 403 for non-admin users")
    void backfillStatusForbidden() throws Exception {
        doThrow(new SecurityException("Admin access required. Current role: USER"))
                .when(embeddingService).verifyAdminRole();

        mockMvc.perform(get("/api/v1/admin/embeddings/backfill/status"))
                .andExpect(status().isForbidden());
    }
}
