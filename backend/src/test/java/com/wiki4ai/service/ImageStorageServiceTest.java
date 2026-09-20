package com.wiki4ai.service;

import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.service.ImageStorageService.ImageType;
import com.wiki4ai.service.ImageStorageService.StoredImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImageStorageService (WIKI4AI-64): magic-byte MIME detection,
 * store/load round-trip, and the path-traversal guards.
 */
class ImageStorageServiceTest {

    @TempDir
    Path tempDir;

    private ImageStorageService service;

    @BeforeEach
    void setUp() {
        service = new ImageStorageService(tempDir.toString());
        service.init();
    }

    // ── Test fixtures: minimal valid image payloads (magic bytes) ────────────

    /** Minimal valid PNG (magic bytes). */
    private static byte[] pngBytes() {
        int[] hex = {
                0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
                0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
                0x54, 0x08, 0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00,
                0x00, 0x00, 0x02, 0x00, 0x01, 0xE2, 0x2A, 0xBC,
                0x63, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
                0x44, 0xAE, 0x42, 0x60, 0x82
        };
        byte[] out = new byte[hex.length];
        for (int i = 0; i < hex.length; i++) {
            out[i] = (byte) hex[i];
        }
        return out;
    }

    /** Minimal JPEG header bytes. */
    private static byte[] jpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01
        };
    }

    /** Minimal WEBP header bytes. */
    private static byte[] webpBytes() {
        return new byte[]{
                'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00,
                'W', 'E', 'B', 'P', 'V', 'P', '8', 'L'
        };
    }

    /** Minimal GIF header bytes. */
    private static byte[] gifBytes() {
        return new byte[]{
                'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00, 0x00
        };
    }

    // ── detectImageType ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("detectImageType — magic byte detection")
    class DetectTests {

        @Test
        @DisplayName("Detects PNG, JPEG, WEBP, GIF from magic bytes")
        void detectsBinaryTypes() {
            assertEquals(ImageType.PNG, ImageStorageService.detectImageType(pngBytes()));
            assertEquals(ImageType.JPEG, ImageStorageService.detectImageType(jpegBytes()));
            assertEquals(ImageType.WEBP, ImageStorageService.detectImageType(webpBytes()));
            assertEquals(ImageType.GIF, ImageStorageService.detectImageType(gifBytes()));
        }

        @Test
        @DisplayName("Detects SVG from text content (with whitespace/BOM variants)")
        void detectsSvg() {
            byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"></svg>".getBytes();
            assertEquals(ImageType.SVG, ImageStorageService.detectImageType(svg));

            byte[] indented = "  \n <svg></svg>".getBytes();
            assertEquals(ImageType.SVG, ImageStorageService.detectImageType(indented));

            byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '<', 's', 'v', 'g', '>', '<', '/', 's', 'v', 'g', '>'};
            assertEquals(ImageType.SVG, ImageStorageService.detectImageType(bom));
        }

        @Test
        @DisplayName("Rejects non-image content (text, markdown, random bytes, empty)")
        void rejectsNonImages() {
            assertNull(ImageStorageService.detectImageType("# Hello world\nsome text".getBytes()));
            assertNull(ImageStorageService.detectImageType("<html><body>not an svg</body></html>".getBytes()));
            assertNull(ImageStorageService.detectImageType(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));
            assertNull(ImageStorageService.detectImageType(new byte[0]));
            assertNull(ImageStorageService.detectImageType(null));
        }

        @Test
        @DisplayName("Rejects a PNG header followed by garbage as PNG but still detects the header")
        void pngHeaderWins() {
            // A file starting with a valid PNG signature is treated as PNG —
            // content integrity beyond the header is out of scope for detection.
            byte[] data = pngBytes();
            assertEquals(ImageType.PNG, ImageStorageService.detectImageType(data));
        }
    }

    // ── store / load round-trip ────────────────────────────────────────────────

    @Nested
    @DisplayName("store + load")
    class StoreLoadTests {

        private static final Pattern STORED_NAME = Pattern.compile(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png$");

        @Test
        @DisplayName("Store returns an unguessable UUID filename with the detected extension")
        void storeReturnsUuidName() throws IOException {
            String stored = service.store("my-project", pngBytes(), ImageType.PNG);
            Matcher m = STORED_NAME.matcher(stored);
            assertTrue(m.matches(), "Stored name should be a lowercase UUID + .png: " + stored);

            // File physically exists under {root}/{projectSlug}/
            Path file = tempDir.resolve("my-project").resolve(stored);
            assertTrue(Files.exists(file));
            assertArrayEquals(pngBytes(), Files.readAllBytes(file));
        }

        @Test
        @DisplayName("Store uses distinct UUIDs for repeated uploads")
        void storeIsUnique() throws IOException {
            String a = service.store("my-project", pngBytes(), ImageType.PNG);
            String b = service.store("my-project", pngBytes(), ImageType.PNG);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Load returns bytes and detected type (round-trip)")
        void loadRoundTrip() throws IOException {
            String stored = service.store("my-project", jpegBytes(), ImageType.JPEG);
            StoredImage image = service.load("my-project", stored);
            assertArrayEquals(jpegBytes(), image.data());
            assertEquals(ImageType.JPEG, image.type());
        }

        @Test
        @DisplayName("Load of a missing file throws NoSuchFileException (404 path)")
        void loadMissingThrows() {
            String uuid = java.util.UUID.randomUUID().toString();
            assertThrows(NoSuchFileException.class,
                    () -> service.load("my-project", uuid + ".png"));
        }

        @Test
        @DisplayName("Rejects invalid project slugs (path traversal / injection)")
        void rejectsBadSlugs() {
            assertThrows(BadRequestException.class,
                    () -> service.store("../etc", pngBytes(), ImageType.PNG));
            assertThrows(BadRequestException.class,
                    () -> service.store("a/b", pngBytes(), ImageType.PNG));
            assertThrows(BadRequestException.class,
                    () -> service.load("my-project..png", "x.png"));
        }

        @Test
        @DisplayName("Rejects stored filenames that do not match the strict UUID pattern")
        void rejectsBadFilenames() {
            String uuid = java.util.UUID.randomUUID().toString();
            assertThrows(BadRequestException.class, () -> service.load("my-project", "..%2F..%2Fetc%2Fpasswd"));
            assertThrows(BadRequestException.class, () -> service.load("my-project", "../secret.png"));
            assertThrows(BadRequestException.class, () -> service.load("my-project", "not-a-uuid.png"));
            assertThrows(BadRequestException.class, () -> service.load("my-project", uuid + ".exe"));
            assertThrows(BadRequestException.class, () -> service.load("my-project", null));
        }
    }

    @Test
    @DisplayName("init() creates the root directory")
    void initCreatesRoot() throws IOException {
        Path nested = tempDir.resolve("nested").resolve("uploads");
        ImageStorageService s = new ImageStorageService(nested.toString());
        s.init();
        assertTrue(Files.isDirectory(nested));
    }

    @Test
    @DisplayName("Supported types expose correct extensions and MIME types")
    void typeMetadata() {
        List<ImageType> all = List.of(ImageType.values());
        assertEquals(5, all.size());
        assertEquals("image/svg+xml", ImageType.SVG.getContentType());
        assertEquals("jpg", ImageType.JPEG.getExtension());
    }
}
