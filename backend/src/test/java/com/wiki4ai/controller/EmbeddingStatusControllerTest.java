package com.wiki4ai.controller;

import com.wiki4ai.service.EmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for {@link EmbeddingStatusController} (sidecar availability).
 */
@WebMvcTest(EmbeddingStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class EmbeddingStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingClient embeddingClient;

    @Test
    @DisplayName("GET /status reports available=true when the sidecar answers")
    void statusAvailable() throws Exception {
        when(embeddingClient.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/v1/embeddings/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.dim").value(1024));
    }

    @Test
    @DisplayName("GET /status reports available=false when the sidecar is down")
    void statusUnavailable() throws Exception {
        when(embeddingClient.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/embeddings/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }
}
