package com.wiki4ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaultEntryRequestDTO Tests")
class VaultEntryRequestDTOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    @DisplayName("JSON Serialization")
    class SerializationTests {

        @Test
        @DisplayName("Should serialize full request to JSON")
        void shouldSerializeFullRequest() throws Exception {
            // given
            VaultEntryRequestDTO dto = createSampleRequest();

            // when
            String json = mapper.writeValueAsString(dto);

            // then
            assertTrue(json.contains("\"title\":\"My Entry\""));
            assertTrue(json.contains("\"url\":\"https://example.com\""));
            assertTrue(json.contains("\"groupPath\":\"work/accounts\""));
            assertTrue(json.contains("\"usernameEncrypted\""));
            assertTrue(json.contains("\"passwordEncrypted\""));
        }

        @Test
        @DisplayName("Should serialize minimal request (title + password only)")
        void shouldSerializeMinimalRequest() throws Exception {
            // given
            VaultEntryRequestDTO dto = VaultEntryRequestDTO.builder()
                    .title("Minimal")
                    .passwordEncrypted(createEncryptedField("pass", "iv"))
                    .build();

            // when
            String json = mapper.writeValueAsString(dto);

            // then
            assertTrue(json.contains("\"title\":\"Minimal\""));
            assertTrue(json.contains("\"passwordEncrypted\""));
        }
    }

    @Nested
    @DisplayName("JSON Deserialization")
    class DeserializationTests {

        @Test
        @DisplayName("Should deserialize full request from JSON")
        void shouldDeserializeFullRequest() throws Exception {
            // given
            String json = """
                    {
                        "title": "My Entry",
                        "usernameEncrypted": {"ciphertext":"dXNlcg==","iv":"aXYx"},
                        "passwordEncrypted": {"ciphertext":"cGFzcw==","iv":"aXYy"},
                        "notesEncrypted": {"ciphertext":"bm90ZXM=","iv":"aXYz","salt":"c2FsdA=="},
                        "url": "https://example.com",
                        "groupPath": "work/accounts"
                    }
                    """;

            // when
            VaultEntryRequestDTO dto = mapper.readValue(json, VaultEntryRequestDTO.class);

            // then
            assertEquals("My Entry", dto.getTitle());
            assertNotNull(dto.getUsernameEncrypted());
            assertEquals("dXNlcg==", dto.getUsernameEncrypted().getCiphertext());
            assertNotNull(dto.getPasswordEncrypted());
            assertEquals("cGFzcw==", dto.getPasswordEncrypted().getCiphertext());
            assertNotNull(dto.getNotesEncrypted());
            assertEquals("bm90ZXM=", dto.getNotesEncrypted().getCiphertext());
            assertEquals("c2FsdA==", dto.getNotesEncrypted().getSalt());
            assertEquals("https://example.com", dto.getUrl());
            assertEquals("work/accounts", dto.getGroupPath());
        }

        @Test
        @DisplayName("Should deserialize minimal request from JSON")
        void shouldDeserializeMinimalRequest() throws Exception {
            // given
            String json = """
                    {"title":"Entry","passwordEncrypted":{"ciphertext":"cGFzcw==","iv":"aXY="}}
                    """;

            // when
            VaultEntryRequestDTO dto = mapper.readValue(json, VaultEntryRequestDTO.class);

            // then
            assertEquals("Entry", dto.getTitle());
            assertNotNull(dto.getPasswordEncrypted());
            assertNull(dto.getUsernameEncrypted());
            assertNull(dto.getNotesEncrypted());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            // when
            VaultEntryRequestDTO dto = createSampleRequest();

            // then
            assertEquals("My Entry", dto.getTitle());
            assertNotNull(dto.getUsernameEncrypted());
            assertNotNull(dto.getPasswordEncrypted());
            assertNotNull(dto.getNotesEncrypted());
            assertEquals("https://example.com", dto.getUrl());
            assertEquals("work/accounts", dto.getGroupPath());
        }

        @Test
        @DisplayName("Should build with required fields only")
        void shouldBuildWithRequiredFieldsOnly() {
            // when
            VaultEntryRequestDTO dto = VaultEntryRequestDTO.builder()
                    .title("Title")
                    .passwordEncrypted(createEncryptedField("pass", "iv"))
                    .build();

            // then
            assertEquals("Title", dto.getTitle());
            assertNotNull(dto.getPasswordEncrypted());
        }
    }

    @Nested
    @DisplayName("Validation annotations")
    class ValidationTests {

        @Test
        @DisplayName("Should have @NotBlank on title")
        void shouldHaveNotBlankOnTitle() throws NoSuchFieldException {
            // given/when/then - verify annotation exists via reflection
            var field = VaultEntryRequestDTO.class.getDeclaredField("title");
            assertNotNull(field.getAnnotation(jakarta.validation.constraints.NotBlank.class));
        }

        @Test
        @DisplayName("Should have @Size on title")
        void shouldHaveSizeOnTitle() throws NoSuchFieldException {
            // given/when/then
            var field = VaultEntryRequestDTO.class.getDeclaredField("title");
            assertNotNull(field.getAnnotation(jakarta.validation.constraints.Size.class));
        }

        @Test
        @DisplayName("Should have @NotNull on passwordEncrypted")
        void shouldHaveNotNullOnPasswordEncrypted() throws NoSuchFieldException {
            // given/when/then
            var field = VaultEntryRequestDTO.class.getDeclaredField("passwordEncrypted");
            assertNotNull(field.getAnnotation(jakarta.validation.constraints.NotNull.class));
        }

        @Test
        @DisplayName("Should have @Size on url")
        void shouldHaveSizeOnUrl() throws NoSuchFieldException {
            // given/when/then
            var field = VaultEntryRequestDTO.class.getDeclaredField("url");
            assertNotNull(field.getAnnotation(jakarta.validation.constraints.Size.class));
        }

        @Test
        @DisplayName("Should have @Size on groupPath")
        void shouldHaveSizeOnGroupPath() throws NoSuchFieldException {
            // given/when/then
            var field = VaultEntryRequestDTO.class.getDeclaredField("groupPath");
            assertNotNull(field.getAnnotation(jakarta.validation.constraints.Size.class));
        }
    }

    private EncryptedField createEncryptedField(String ciphertext, String iv) {
        return EncryptedField.builder()
                .ciphertext(ciphertext)
                .iv(iv)
                .build();
    }

    private VaultEntryRequestDTO createSampleRequest() {
        return VaultEntryRequestDTO.builder()
                .title("My Entry")
                .usernameEncrypted(createEncryptedField("dXNlcg==", "aXYx"))
                .passwordEncrypted(createEncryptedField("cGFzcw==", "aXYy"))
                .notesEncrypted(EncryptedField.builder()
                        .ciphertext("bm90ZXM=")
                        .iv("aXYz")
                        .salt("c2FsdA==")
                        .build())
                .url("https://example.com")
                .groupPath("work/accounts")
                .build();
    }
}
