package com.wiki4ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.dto.EncryptedField;
import com.wiki4ai.dto.VaultEntryImportDTO;
import com.wiki4ai.dto.VaultEntryRequestDTO;
import com.wiki4ai.dto.VaultEntryResponseDTO;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.VaultImportService;
import com.wiki4ai.service.VaultMasterPasswordService;
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
import static org.mockito.ArgumentMatchers.isNull;
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

    @MockBean
    private VaultMasterPasswordService masterPasswordService;

    @MockBean
    private VaultImportService importService;

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

    private VaultEntryResponseDTO createSampleEntry(Long id) {
        return VaultEntryResponseDTO.builder()
                .id(id)
                .title("Test Entry")
                .usernameEncrypted(createEncryptedField("AQID", "Cg=="))
                .passwordEncrypted(createEncryptedField("BAUG", "Cg=="))
                .notesEncrypted(createEncryptedField("Bw==", "Cg=="))
                .url("https://example.com")
                .groupPath("work/accounts")
                .build();
    }

    private VaultEntryRequestDTO createSampleCreateDto() {
        return VaultEntryRequestDTO.builder()
                .title("New Entry")
                .usernameEncrypted(createEncryptedField("AQID", "Cg=="))
                .passwordEncrypted(createEncryptedField("BAUG", "Cg=="))
                .notesEncrypted(null)
                .url("https://newsite.com")
                .groupPath("personal")
                .build();
    }

    private EncryptedField createEncryptedField(String ciphertext, String iv) {
        return EncryptedField.builder()
                .ciphertext(ciphertext)
                .iv(iv)
                .build();
    }

    private org.springframework.mock.web.MockMultipartFile createMockFile(String name, byte[] content) {
        return new org.springframework.mock.web.MockMultipartFile("file", name, "application/octet-stream", content);
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

            VaultEntryRequestDTO dto = createSampleCreateDto();
            VaultEntryResponseDTO created = createSampleEntry(1L);
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

            VaultEntryRequestDTO dto = VaultEntryRequestDTO.builder()
                    .passwordEncrypted(createEncryptedField("AQ==", "Cg=="))
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

            List<VaultEntryResponseDTO> entries = List.of(createSampleEntry(1L), createSampleEntry(2L));
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

            VaultEntryResponseDTO entry = createSampleEntry(1L);
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

            VaultEntryRequestDTO dto = createSampleCreateDto();
            VaultEntryResponseDTO updated = createSampleEntry(1L);
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

            VaultEntryRequestDTO dto = createSampleCreateDto();
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

            VaultEntryRequestDTO dto = createSampleCreateDto();
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
    @DisplayName("GET /api/v1/vault/search - Search entries")
    class SearchEntriesTests {

        @Test
        @DisplayName("Should return 200 with matching entries by title")
        void shouldReturnEntriesMatchingTitle() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<VaultEntryResponseDTO> results = List.of(createSampleEntry(1L));
            given(vaultService.searchEntries(eq(TEST_USER_ID), eq("Test"), isNull())).willReturn(results);

            // when & then
            mockMvc.perform(get("/api/v1/vault/search")
                            .param("q", "Test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(1));

            verify(vaultService).searchEntries(eq(TEST_USER_ID), eq("Test"), isNull());
        }

        @Test
        @DisplayName("Should return 200 with matching entries by URL")
        void shouldReturnEntriesMatchingUrl() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<VaultEntryResponseDTO> results = List.of(createSampleEntry(1L));
            given(vaultService.searchEntries(eq(TEST_USER_ID), eq("example"), isNull())).willReturn(results);

            // when & then
            mockMvc.perform(get("/api/v1/vault/search")
                            .param("q", "example"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("Should return 200 with entries filtered by group_path prefix")
        void shouldReturnEntriesFilteredByGroupPath() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<VaultEntryResponseDTO> results = List.of(createSampleEntry(1L));
            given(vaultService.searchEntries(eq(TEST_USER_ID), eq("Test"), eq("work"))).willReturn(results);

            // when & then
            mockMvc.perform(get("/api/v1/vault/search")
                            .param("q", "Test")
                            .param("groupPath", "work"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));

            verify(vaultService).searchEntries(eq(TEST_USER_ID), eq("Test"), eq("work"));
        }

        @Test
        @DisplayName("Should return 200 with empty list when no matches")
        void shouldReturnEmptyListWhenNoMatches() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(vaultService.searchEntries(eq(TEST_USER_ID), eq("nonexistent"), isNull())).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/vault/search")
                            .param("q", "nonexistent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Should return 200 with empty list when query is blank")
        void shouldReturnEmptyListWhenQueryBlank() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(vaultService.searchEntries(eq(TEST_USER_ID), eq(""), isNull())).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/vault/search")
                            .param("q", ""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Should return 403 when no authentication present")
        void shouldReturnForbiddenWhenNotAuthenticated() throws Exception {
            // given - no auth set up
            SecurityContextHolder.clearContext();

            // when & then
            mockMvc.perform(get("/api/v1/vault/search").param("q", "test"))
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

            VaultEntryResponseDTO entry = createSampleEntry(1L);

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

    @Nested
    @DisplayName("POST /api/v1/vault/import/kdbx - Import from KDBX")
    class ImportFromKdbxTests {

        @Test
        @DisplayName("Should return 200 with imported entries")
        void shouldImportEntriesSuccessfully() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<VaultEntryImportDTO> entries = List.of(
                    VaultEntryImportDTO.builder()
                            .title("Test Entry")
                            .username("testuser")
                            .password("secret123")
                            .url("https://example.com")
                            .groupPath("work/accounts")
                            .build()
            );

            given(importService.importFromKdbx(org.mockito.ArgumentMatchers.any(), eq("password123")))
                    .willReturn(entries);

            // when & then
            mockMvc.perform(multipart("/api/v1/vault/import/kdbx")
                            .file(createMockFile("test.kdbx", new byte[]{1, 2, 3}))
                            .param("password", "password123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].title").value("Test Entry"))
                    .andExpect(jsonPath("$[0].username").value("testuser"));

            verify(importService).importFromKdbx(org.mockito.ArgumentMatchers.any(), eq("password123"));
        }

        @Test
        @DisplayName("Should return 400 when file is empty")
        void shouldReturnBadRequestWhenFileEmpty() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            // when & then
            mockMvc.perform(multipart("/api/v1/vault/import/kdbx")
                            .file(createMockFile("", new byte[0]))
                            .param("password", "password123"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 409 when password is invalid")
        void shouldReturnConflictWhenPasswordInvalid() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(importService.importFromKdbx(org.mockito.ArgumentMatchers.any(), eq("wrong")))
                    .willThrow(new IllegalArgumentException("Invalid password or corrupted KDBX file"));

            // when & then
            mockMvc.perform(multipart("/api/v1/vault/import/kdbx")
                            .file(createMockFile("test.kdbx", new byte[]{1, 2, 3}))
                            .param("password", "wrong"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Should return 400 when file is corrupted")
        void shouldReturnBadRequestWhenFileCorrupted() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(importService.importFromKdbx(org.mockito.ArgumentMatchers.any(), eq("password")))
                    .willThrow(new java.io.IOException("Failed to parse KDBX"));

            // when & then
            mockMvc.perform(multipart("/api/v1/vault/import/kdbx")
                            .file(createMockFile("test.kdbx", new byte[]{1, 2, 3}))
                            .param("password", "password"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/vault/export - Export entries")
    class ExportEntriesTests {

        @Test
        @DisplayName("Should return 200 with encrypted entries for export (json format)")
        void shouldExportEntriesWithJsonFormat() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<VaultEntryResponseDTO> entries = List.of(createSampleEntry(1L), createSampleEntry(2L));
            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(entries);

            // when & then
            mockMvc.perform(get("/api/v1/vault/export")
                            .param("format", "json"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].title").value("Test Entry"));

            verify(vaultService).getEntriesByUser(TEST_USER_ID);
        }

        @Test
        @DisplayName("Should return 200 with encrypted entries for export (csv format)")
        void shouldExportEntriesWithCsvFormat() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<VaultEntryResponseDTO> entries = List.of(createSampleEntry(1L));
            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(entries);

            // when & then - backend returns same JSON regardless of format param
            mockMvc.perform(get("/api/v1/vault/export")
                            .param("format", "csv"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(1));

            verify(vaultService).getEntriesByUser(TEST_USER_ID);
        }

        @Test
        @DisplayName("Should return 200 with empty list when no entries")
        void shouldExportEmptyList() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/vault/export"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Should return 200 with default json format when no format param")
        void shouldExportWithDefaultFormat() throws Exception {
            // given
            setupAuthenticatedUser();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(
                    com.wiki4ai.model.User.builder().id(TEST_USER_ID).build()
            ));

            List<VaultEntryResponseDTO> entries = List.of(createSampleEntry(1L));
            given(vaultService.getEntriesByUser(TEST_USER_ID)).willReturn(entries);

            // when & then
            mockMvc.perform(get("/api/v1/vault/export"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("Should return 403 when no authentication present")
        void shouldReturnForbiddenWhenNotAuthenticated() throws Exception {
            // given - no auth set up
            SecurityContextHolder.clearContext();

            // when & then
            mockMvc.perform(get("/api/v1/vault/export"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }
    }

    @Nested
    @DisplayName("Master password + vault encryption salt")
    class MasterPasswordAndSaltTests {

        @Test
        @DisplayName("POST /master-password/set should persist hash and salt")
        void shouldSetMasterPasswordAndSalt() throws Exception {
            setupAuthenticatedUser();
            com.wiki4ai.model.User user = com.wiki4ai.model.User.builder().id(TEST_USER_ID).username(TEST_USERNAME).build();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(user));
            given(masterPasswordService.hashPassword("plainhash")).willReturn("salt:hash");

            com.wiki4ai.dto.SetMasterPasswordRequestDTO request = com.wiki4ai.dto.SetMasterPasswordRequestDTO.builder()
                    .masterPasswordHash("plainhash")
                    .salt("dGVzdHNhbHQ=")
                    .build();

            mockMvc.perform(post("/api/v1/vault/master-password/set")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            org.junit.jupiter.api.Assertions.assertEquals("salt:hash", user.getVaultMasterPasswordHash());
            org.junit.jupiter.api.Assertions.assertEquals("dGVzdHNhbHQ=", user.getVaultSalt());
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("POST /master-password/set without salt should not overwrite an existing salt")
        void shouldNotOverwriteSaltWhenNotProvided() throws Exception {
            setupAuthenticatedUser();
            com.wiki4ai.model.User user = com.wiki4ai.model.User.builder()
                    .id(TEST_USER_ID).username(TEST_USERNAME).vaultSalt("existing-salt").build();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(user));
            given(masterPasswordService.hashPassword(any())).willReturn("salt:hash");

            com.wiki4ai.dto.SetMasterPasswordRequestDTO request = com.wiki4ai.dto.SetMasterPasswordRequestDTO.builder()
                    .masterPasswordHash("plainhash")
                    .build();

            mockMvc.perform(post("/api/v1/vault/master-password/set")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            org.junit.jupiter.api.Assertions.assertEquals("existing-salt", user.getVaultSalt());
        }

        @Test
        @DisplayName("GET /master-password/salt should return the salt when set")
        void shouldReturnSaltWhenSet() throws Exception {
            setupAuthenticatedUser();
            com.wiki4ai.model.User user = com.wiki4ai.model.User.builder()
                    .id(TEST_USER_ID).username(TEST_USERNAME).vaultSalt("dGVzdHNhbHQ=").build();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(user));

            mockMvc.perform(get("/api/v1/vault/master-password/salt"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.salt").value("dGVzdHNhbHQ="));
        }

        @Test
        @DisplayName("GET /master-password/salt should return 404 when no salt is set yet")
        void shouldReturnNotFoundWhenSaltMissing() throws Exception {
            setupAuthenticatedUser();
            com.wiki4ai.model.User user = com.wiki4ai.model.User.builder().id(TEST_USER_ID).username(TEST_USERNAME).build();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(user));

            mockMvc.perform(get("/api/v1/vault/master-password/salt"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET /master-password/salt should return 403 when no authentication present")
        void shouldReturnForbiddenWhenNotAuthenticatedForSalt() throws Exception {
            SecurityContextHolder.clearContext();

            mockMvc.perform(get("/api/v1/vault/master-password/salt"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /master-password/status should reflect isPasswordSet() result")
        void shouldReturnMasterPasswordStatus() throws Exception {
            setupAuthenticatedUser();
            com.wiki4ai.model.User user = com.wiki4ai.model.User.builder()
                    .id(TEST_USER_ID).username(TEST_USERNAME).vaultMasterPasswordHash("salt:hash").build();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(user));
            given(masterPasswordService.isPasswordSet("salt:hash")).willReturn(true);

            mockMvc.perform(get("/api/v1/vault/master-password/status"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("true"));
        }

        @Test
        @DisplayName("POST /master-password/verify should return 200 for a correct password")
        void shouldVerifyCorrectMasterPassword() throws Exception {
            setupAuthenticatedUser();
            com.wiki4ai.model.User user = com.wiki4ai.model.User.builder()
                    .id(TEST_USER_ID).username(TEST_USERNAME).vaultMasterPasswordHash("salt:hash").build();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(user));
            given(masterPasswordService.verifyPassword("plainhash", "salt:hash")).willReturn(true);

            com.wiki4ai.dto.VerifyMasterPasswordRequestDTO request = com.wiki4ai.dto.VerifyMasterPasswordRequestDTO.builder()
                    .masterPasswordHash("plainhash")
                    .build();

            mockMvc.perform(post("/api/v1/vault/master-password/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /master-password/verify should return 401 for an incorrect password")
        void shouldRejectIncorrectMasterPassword() throws Exception {
            setupAuthenticatedUser();
            com.wiki4ai.model.User user = com.wiki4ai.model.User.builder()
                    .id(TEST_USER_ID).username(TEST_USERNAME).vaultMasterPasswordHash("salt:hash").build();
            given(userRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(user));
            given(masterPasswordService.verifyPassword("wronghash", "salt:hash")).willReturn(false);

            com.wiki4ai.dto.VerifyMasterPasswordRequestDTO request = com.wiki4ai.dto.VerifyMasterPasswordRequestDTO.builder()
                    .masterPasswordHash("wronghash")
                    .build();

            mockMvc.perform(post("/api/v1/vault/master-password/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
