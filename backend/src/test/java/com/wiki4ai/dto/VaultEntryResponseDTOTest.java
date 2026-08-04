package com.wiki4ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaultEntryResponseDTO Tests")
class VaultEntryResponseDTOTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @Nested
    @DisplayName("JSON Serialization")
    class SerializationTests {

        @Test
        @DisplayName("Should serialize full response to JSON")
        void shouldSerializeFullResponse() throws Exception {
            // given
            VaultEntryResponseDTO dto = createSampleResponse();

            // when
            String json = mapper.writeValueAsString(dto);

            // then
            assertTrue(json.contains("\"id\":1"));
            assertTrue(json.contains("\"title\":\"My Entry\""));
            assertTrue(json.contains("\"url\":\"https://example.com\""));
            assertTrue(json.contains("\"groupPath\":\"work/accounts\""));
            assertTrue(json.contains("\"createdAt\""));
            assertTrue(json.contains("\"updatedAt\""));
        }

        @Test
        @DisplayName("Should serialize encrypted fields as nested objects")
        void shouldSerializeEncryptedFieldsAsNestedObjects() throws Exception {
            // given
            VaultEntryResponseDTO dto = createSampleResponse();

            // when
            String json = mapper.writeValueAsString(dto);

            // then
            assertTrue(json.contains("\"passwordEncrypted\":{\"ciphertext\""));
            assertTrue(json.contains("\"iv\""));
        }

        @Test
        @DisplayName("Should serialize null encrypted fields")
        void shouldSerializeNullEncryptedFields() throws Exception {
            // given
            VaultEntryResponseDTO dto = VaultEntryResponseDTO.builder()
                    .id(1L)
                    .title("Minimal")
                    .passwordEncrypted(createEncryptedField("pass", "iv"))
                    .build();

            // when
            String json = mapper.writeValueAsString(dto);

            // then
            assertTrue(json.contains("\"usernameEncrypted\":null"));
            assertTrue(json.contains("\"notesEncrypted\":null"));
        }
    }

    @Nested
    @DisplayName("JSON Deserialization")
    class DeserializationTests {

        @Test
        @DisplayName("Should deserialize full response from JSON")
        void shouldDeserializeFullResponse() throws Exception {
            // given
            LocalDateTime now = LocalDateTime.of(2026, 1, 15, 10, 30, 0);
            String json = """
                    {
                        "id": 42,
                        "title": "My Entry",
                        "usernameEncrypted": {"ciphertext":"dXNlcg==","iv":"aXYx"},
                        "passwordEncrypted": {"ciphertext":"cGFzcw==","iv":"aXYy"},
                        "notesEncrypted": {"ciphertext":"bm90ZXM=","iv":"aXYz","salt":"c2FsdA=="},
                        "url": "https://example.com",
                        "groupPath": "work/accounts",
                        "createdAt": "2026-01-15T10:30:00",
                        "updatedAt": "2026-01-15T10:30:00"
                    }
                    """;

            // when
            VaultEntryResponseDTO dto = mapper.readValue(json, VaultEntryResponseDTO.class);

            // then
            assertEquals(42L, dto.getId());
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
        @DisplayName("Should deserialize with null optional fields")
        void shouldDeserializeWithNullOptionalFields() throws Exception {
            // given
            String json = """
                    {"id":1,"title":"Entry","passwordEncrypted":{"ciphertext":"cGFzcw==","iv":"aXY="}}
                    """;

            // when
            VaultEntryResponseDTO dto = mapper.readValue(json, VaultEntryResponseDTO.class);

            // then
            assertEquals(1L, dto.getId());
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
            VaultEntryResponseDTO dto = createSampleResponse();

            // then
            assertEquals(1L, dto.getId());
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
            VaultEntryResponseDTO dto = VaultEntryResponseDTO.builder()
                    .id(1L)
                    .title("Title")
                    .passwordEncrypted(createEncryptedField("pass", "iv"))
                    .build();

            // then
            assertEquals(1L, dto.getId());
            assertEquals("Title", dto.getTitle());
            assertNotNull(dto.getPasswordEncrypted());
        }
    }

    @Nested
    @DisplayName("Round-trip serialization")
    class RoundTripTests {

        @Test
        @DisplayName("Should survive serialize -> deserialize round trip")
        void shouldSurviveRoundTrip() throws Exception {
            // given
            VaultEntryResponseDTO original = createSampleResponse();

            // when
            String json = mapper.writeValueAsString(original);
            VaultEntryResponseDTO deserialized = mapper.readValue(json, VaultEntryResponseDTO.class);

            // then
            assertEquals(original.getId(), deserialized.getId());
            assertEquals(original.getTitle(), deserialized.getTitle());
            assertEquals(original.getUrl(), deserialized.getUrl());
            assertEquals(original.getGroupPath(), deserialized.getGroupPath());
            assertEquals(original.getUsernameEncrypted().getCiphertext(), deserialized.getUsernameEncrypted().getCiphertext());
            assertEquals(original.getPasswordEncrypted().getCiphertext(), deserialized.getPasswordEncrypted().getCiphertext());
        }

        @Test
        @DisplayName("Should handle real encrypted data round trip")
        void shouldHandleRealEncryptedDataRoundTrip() throws Exception {
            // given - simulate real encryption scenario
            byte[] passwordBytes = "super secret".getBytes();
            String ciphertext = Base64.getEncoder().encodeToString(passwordBytes);
            byte[] ivBytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
            String iv = Base64.getEncoder().encodeToString(ivBytes);

            VaultEntryResponseDTO original = VaultEntryResponseDTO.builder()
                    .id(1L)
                    .title("Secure Entry")
                    .passwordEncrypted(EncryptedField.builder()
                            .ciphertext(ciphertext)
                            .iv(iv)
                            .build())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            VaultEntryResponseDTO deserialized = mapper.readValue(json, VaultEntryResponseDTO.class);

            // then
            assertEquals(ciphertext, deserialized.getPasswordEncrypted().getCiphertext());
            assertEquals(iv, deserialized.getPasswordEncrypted().getIv());

            byte[] decodedPassword = Base64.getDecoder().decode(deserialized.getPasswordEncrypted().getCiphertext());
            assertArrayEquals(passwordBytes, decodedPassword);
        }
    }

    private EncryptedField createEncryptedField(String ciphertext, String iv) {
        return EncryptedField.builder()
                .ciphertext(ciphertext)
                .iv(iv)
                .build();
    }

    private VaultEntryResponseDTO createSampleResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 15, 10, 30, 0);
        return VaultEntryResponseDTO.builder()
                .id(1L)
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
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
