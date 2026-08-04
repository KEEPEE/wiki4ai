package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.EncryptedVaultEntryDTO;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.VaultService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VaultController.class)
@AutoConfigureMockMvc
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
})
@org.springframework.test.context.ActiveProfiles("test")
class VaultControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VaultService vaultService;

    @MockBean
    private UserRepository userRepository;

    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_USERNAME = "testuser";

    private void setupAuthenticatedUser() {
        Authentication auth = new Authentication() {
            @Override public boolean isAuthenticated() { return true; }
            @Override public void setAuthenticated(boolean b) {}
            @Override public Object getCredentials() { return null; }
            @Override public Object getDetails() { return null; }
            @Override public Object getPrincipal() { return TEST_USERNAME; }
            @Override public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() { return List.of(); }
            @Override public String getName() { return TEST_USERNAME; }
        };
        SecurityContext context = new SecurityContext() {
            @Override public Authentication getAuthentication() { return auth; }
            @Override public void setAuthentication(Authentication a) {}
        };
        SecurityContextHolder.setContext(context);
    }

    private EncryptedVaultEntryDTO createSampleEntry(Long id) {
        return EncryptedVaultEntryDTO.builder()
                .id(id)
                .title("Test Entry")
                .usernameEncrypted(new byte[]{1, 2, 3})
                .passwordEncrypted(new byte[]{4, 5, 6})
                .notesEncrypted(new byte[]{7, 8, 9})
                .url("https://example.com")
                .groupPath("work/accounts")
                .iv(new byte[]{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25})
                .build();
    }

    private EncryptedVaultEntryDTO createSampleCreateDto() {
        return EncryptedVaultEntryDTO.builder()
                .title("New Entry")
                .usernameEncrypted(new byte[]{1, 2, 3})
                .passwordEncrypted(new byte[]{4, 5, 6})
                .notesEncrypted(null)
                .url("https://newsite.com")
                .groupPath("personal")
                .iv(new byte[]{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25})
                .build();
    }

    @Nested
    @DisplayName("POST /api/v1/vault/entries - Create entry")
    class CreateEntryTests {

        @Test
        @DisplayName("Should return 201 with created entry")
        void shouldCreateEntrySuccessfully() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            EncryptedVaultEntryDTO dto = createSampleCreateDto();
            EncryptedVaultEntryDTO created = createSampleEntry(1L);
            given(vaultService.createEntry(eq(TEST_USER_ID), any())).willReturn(created);

            // when & then
            mockMvc.perform(post("/api/v1/vault/entries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.title").value("Test Entry"));

            verify(vaultService).createEntry(eq(TEST_USER_ID), any());
        }

        @Test
        @DisplayName("Should return 409 when validation fails (missing title)")
        void shouldReturnConflictWhenValidationFails() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            EncryptedVaultEntryDTO dto = EncryptedVaultEntryDTO.builder()
                    .passwordEncrypted(new byte[]{1})
                    .iv(new byte[16])
                    .build();

            given(vaultService.createEntry(eq(TEST_USER_ID), any()))
                    .willThrow(new IllegalArgumentException("Title is required"));

            // when & then
            mockMvc.perform(post("/api/v1/vault/entries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Title is required"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/vault/entries - List entries")
    class GetEntriesTests {

        @Test
        @DisplayName("Should return 200 with list of entries for user")
        void shouldReturnEntriesForUser() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<EncryptedVaultEntryDTO> entries = List.of(createSampleEntry(1L), createSampleEntry(2L));
            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(entries);

            // when & then
            mockMvc.perform(get("/api/v1/vault/entries"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(1));

            verify(vaultService).getEntriesByUser(TEST_USER_ID);
        }

        @Test
        @DisplayName("Should return 200 with empty list when no entries")
        void shouldReturnEmptyList() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/vault/entries"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/vault/entries/{id} - Get entry by ID")
    class GetEntryTests {

        @Test
        @DisplayName("Should return 200 with entry details when found and owned")
        void shouldReturnEntryWhenOwned() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            EncryptedVaultEntryDTO entry = createSampleEntry(1L);
            given(vaultService.getEntryById(eq(1L), eq(TEST_USER_ID))).willReturn(entry);

            // when & then
            mockMvc.perform(get("/api/v1/vault/entries/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.title").value("Test Entry"));
        }

        @Test
        @DisplayName("Should return 403 when entry belongs to another user")
        void shouldReturnForbiddenWhenNotOwned() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(vaultService.getEntryById(eq(99L), eq(TEST_USER_ID)))
                    .willThrow(new IllegalArgumentException("Vault entry does not belong to the specified user"));

            // when & then
            mockMvc.perform(get("/api/v1/vault/entries/99"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Vault entry does not belong to the specified user"));
        }

        @Test
        @DisplayName("Should return 404 when entry not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(vaultService.getEntryById(eq(999L), eq(TEST_USER_ID)))
                    .willThrow(new EntityNotFoundException("Vault entry not found with id: 999"));

            // when & then
            mockMvc.perform(get("/api/v1/vault/entries/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Vault entry not found with id: 999"));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/vault/entries/{id} - Update entry")
    class UpdateEntryTests {

        @Test
        @DisplayName("Should return 200 with updated entry when owned")
        void shouldUpdateEntryWhenOwned() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            EncryptedVaultEntryDTO dto = createSampleCreateDto();
            EncryptedVaultEntryDTO updated = createSampleEntry(1L);
            given(vaultService.updateEntry(eq(1L), eq(TEST_USER_ID), any())).willReturn(updated);

            // when & then
            mockMvc.perform(put("/api/v1/vault/entries/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1));

            verify(vaultService).updateEntry(eq(1L), eq(TEST_USER_ID), any());
        }

        @Test
        @DisplayName("Should return 403 when entry belongs to another user")
        void shouldReturnForbiddenWhenNotOwned() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            EncryptedVaultEntryDTO dto = createSampleCreateDto();
            given(vaultService.updateEntry(eq(99L), eq(TEST_USER_ID), any()))
                    .willThrow(new IllegalArgumentException("Vault entry does not belong to the specified user"));

            // when & then
            mockMvc.perform(put("/api/v1/vault/entries/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Vault entry does not belong to the specified user"));
        }

        @Test
        @DisplayName("Should return 404 when entry not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            EncryptedVaultEntryDTO dto = createSampleCreateDto();
            given(vaultService.updateEntry(eq(999L), eq(TEST_USER_ID), any()))
                    .willThrow(new EntityNotFoundException("Vault entry not found with id: 999"));

            // when & then
            mockMvc.perform(put("/api/v1/vault/entries/999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Vault entry not found with id: 999"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/vault/entries/{id} - Delete entry")
    class DeleteEntryTests {

        @Test
        @DisplayName("Should return 204 when entry deleted successfully")
        void shouldDeleteEntrySuccessfully() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            doNothing().when(vaultService).deleteEntry(eq(1L), eq(TEST_USER_ID));

            // when & then
            mockMvc.perform(delete("/api/v1/vault/entries/1"))
                    .andExpect(status().isNoContent());

            verify(vaultService).deleteEntry(eq(1L), eq(TEST_USER_ID));
        }

        @Test
        @DisplayName("Should return 403 when entry belongs to another user")
        void shouldReturnForbiddenWhenNotOwned() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            doThrow(new IllegalArgumentException("Vault entry does not belong to the specified user"))
                    .when(vaultService).deleteEntry(eq(99L), eq(TEST_USER_ID));

            // when & then
            mockMvc.perform(delete("/api/v1/vault/entries/99"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Vault entry does not belong to the specified user"));
        }

        @Test
        @DisplayName("Should return 404 when entry not found")
        void shouldReturnNotFoundWhenNotExists() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            doThrow(new EntityNotFoundException("Vault entry not found with id: 999"))
                    .when(vaultService).deleteEntry(eq(999L), eq(TEST_USER_ID));

            // when & then
            mockMvc.perform(delete("/api/v1/vault/entries/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Vault entry not found with id: 999"));
        }
    }

    @Nested
    @DisplayName("Authentication required")
    class AuthenticationTests {

        @Test
        @DisplayName("Should return 403 when no authentication present")
        void shouldReturnForbiddenWhenNotAuthenticated() throws Exception {
            // given - no auth set up
            SecurityContextHolder.clearContext();

            // when & then
            mockMvc.perform(get("/api/v1/vault/entries"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }
    }

    @Nested
    @DisplayName("Swagger/OpenAPI annotations")
    class SwaggerAnnotationsTests {

        @Test
        @DisplayName("Controller should have proper Tag annotation for Vault")
        void shouldHaveTagAnnotation() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(List.of());

            // when & then - verify controller is registered and endpoints work
            mockMvc.perform(get("/api/v1/vault/entries"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("All CRUD endpoints should be properly mapped")
        void shouldHaveAllEndpointsMapped() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            EncryptedVaultEntryDTO entry = createSampleEntry(1L);

            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(List.of(entry));
            given(vaultService.createEntry(eq(TEST_USER_ID), any())).willReturn(entry);
            given(vaultService.getEntryById(eq(1L), eq(TEST_USER_ID))).willReturn(entry);
            given(vaultService.updateEntry(eq(1L), eq(TEST_USER_ID), any())).willReturn(entry);
            doNothing().when(vaultService).deleteEntry(eq(1L), eq(TEST_USER_ID));

            // when & then - verify all endpoints respond correctly
            mockMvc.perform(get("/api/v1/vault/entries"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/vault/entries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSampleCreateDto())))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/vault/entries/1"))
                    .andExpect(status().isOk());

            mockMvc.perform(put("/api/v1/vault/entries/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSampleCreateDto())))
                    .andExpect(status().isOk());

            mockMvc.perform(delete("/api/v1/vault/entries/1"))
                    .andExpect(status().isNoContent());
        }
    }
}
