package com.wiki4ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EncryptedField DTO Tests")
class EncryptedFieldTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    @DisplayName("JSON Serialization")
    class SerializationTests {

        @Test
        @DisplayName("Should serialize all fields to JSON")
        void shouldSerializeAllFields() throws Exception {
            // given
            EncryptedField field = EncryptedField.builder()
                    .ciphertext("dGVzdA==")
                    .iv("YWJjZGVmZw==")
                    .salt("c2FsdDEyMw==")
                    .build();

            // when
            String json = mapper.writeValueAsString(field);

            // then
            assertTrue(json.contains("\"ciphertext\":\"dGVzdA==\""));
            assertTrue(json.contains("\"iv\":\"YWJjZGVmZw==\""));
            assertTrue(json.contains("\"salt\":\"c2FsdDEyMw==\""));
        }

        @Test
        @DisplayName("Should serialize with null salt")
        void shouldSerializeWithNullSalt() throws Exception {
            // given
            EncryptedField field = EncryptedField.builder()
                    .ciphertext("dGVzdA==")
                    .iv("YWJjZGVmZw==")
                    .build();

            // when
            String json = mapper.writeValueAsString(field);

            // then
            assertTrue(json.contains("\"ciphertext\":\"dGVzdA==\""));
            assertTrue(json.contains("\"iv\":\"YWJjZGVmZw==\""));
        }
    }

    @Nested
    @DisplayName("JSON Deserialization")
    class DeserializationTests {

        @Test
        @DisplayName("Should deserialize all fields from JSON")
        void shouldDeserializeAllFields() throws Exception {
            // given
            String json = """
                    {"ciphertext":"dGVzdA==","iv":"YWJjZGVmZw==","salt":"c2FsdDEyMw=="}
                    """;

            // when
            EncryptedField field = mapper.readValue(json, EncryptedField.class);

            // then
            assertEquals("dGVzdA==", field.getCiphertext());
            assertEquals("YWJjZGVmZw==", field.getIv());
            assertEquals("c2FsdDEyMw==", field.getSalt());
        }

        @Test
        @DisplayName("Should deserialize without salt")
        void shouldDeserializeWithoutSalt() throws Exception {
            // given
            String json = """
                    {"ciphertext":"dGVzdA==","iv":"YWJjZGVmZw=="}
                    """;

            // when
            EncryptedField field = mapper.readValue(json, EncryptedField.class);

            // then
            assertEquals("dGVzdA==", field.getCiphertext());
            assertEquals("YWJjZGVmZw==", field.getIv());
            assertNull(field.getSalt());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            // when
            EncryptedField field = EncryptedField.builder()
                    .ciphertext("cipher")
                    .iv("ivvalue")
                    .salt("saltvalue")
                    .build();

            // then
            assertEquals("cipher", field.getCiphertext());
            assertEquals("ivvalue", field.getIv());
            assertEquals("saltvalue", field.getSalt());
        }

        @Test
        @DisplayName("Should build with required fields only")
        void shouldBuildWithRequiredFieldsOnly() {
            // when
            EncryptedField field = EncryptedField.builder()
                    .ciphertext("cipher")
                    .iv("ivvalue")
                    .build();

            // then
            assertEquals("cipher", field.getCiphertext());
            assertEquals("ivvalue", field.getIv());
            assertNull(field.getSalt());
        }
    }

    @Nested
    @DisplayName("Base64 encoded data handling")
    class Base64Tests {

        @Test
        @DisplayName("Should handle real encrypted data (Base64)")
        void shouldHandleRealEncryptedData() throws Exception {
            // given - simulate real encryption scenario
            byte[] plaintext = "secret password".getBytes();
            String ciphertext = Base64.getEncoder().encodeToString(plaintext);
            byte[] ivBytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
            String iv = Base64.getEncoder().encodeToString(ivBytes);

            EncryptedField field = EncryptedField.builder()
                    .ciphertext(ciphertext)
                    .iv(iv)
                    .build();

            // when - serialize and deserialize
            String json = mapper.writeValueAsString(field);
            EncryptedField deserialized = mapper.readValue(json, EncryptedField.class);

            // then
            assertEquals(ciphertext, deserialized.getCiphertext());
            assertEquals(iv, deserialized.getIv());

            byte[] decodedCiphertext = Base64.getDecoder().decode(deserialized.getCiphertext());
            assertArrayEquals(plaintext, decodedCiphertext);

            byte[] decodedIv = Base64.getDecoder().decode(deserialized.getIv());
            assertArrayEquals(ivBytes, decodedIv);
        }
    }
}
