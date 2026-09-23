package com.wiki4ai.service;

import com.wiki4ai.exception.BadRequestException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * File storage for uploaded images (WIKI4AI-64).
 *
 * Images are stored under a configurable root directory (env var UPLOAD_DIR,
 * property upload.dir) as {projectSlug}/{uuid}.{ext}. The UUID name is
 * unguessable — but the path alone is NOT security: serving requires a valid
 * JWT (see ImageController + SecurityConfig), and the frontend renders images
 * by fetching them with the Bearer token and converting to a blob URL.
 *
 * MIME type is detected from magic bytes (never from the client-provided
 * Content-Type or file extension), which makes the stored extension always
 * consistent with the actual content.
 */
@Slf4j
@Service
public class ImageStorageService {

    /** Supported image types with their canonical extensions and MIME types. */
    public enum ImageType {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
        WEBP("webp", "image/webp"),
        GIF("gif", "image/gif"),
        SVG("svg", "image/svg+xml");

        private final String extension;
        private final String contentType;

        ImageType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String getExtension() {
            return extension;
        }

        public String getContentType() {
            return contentType;
        }
    }

    /** A stored image: raw bytes plus the MIME type to serve with. */
    public record StoredImage(byte[] data, ImageType type) {
    }

    /**
     * Strict shape of a stored image filename: lowercase UUID + allowed extension.
     * Anything else (path separators, dots, uppercase, ..) is rejected before any
     * filesystem access — this is the path-traversal guard for the read endpoint.
     */
    private static final Pattern STORED_FILENAME =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpe?g|webp|gif|svg)$");

    /** Project slugs are URL-friendly: lowercase alphanumerics and hyphens. */
    private static final Pattern PROJECT_SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final Path rootDir;

    public ImageStorageService(@Value("${upload.dir:/uploads}") String uploadDir) {
        this.rootDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(rootDir);
            log.info("Image storage root initialized at {}", rootDir);
        } catch (IOException e) {
            log.warn("Could not create image storage root {} — it will be created lazily on first upload: {}",
                    rootDir, e.getMessage());
        }
    }

    /**
     * Detect the image type from magic bytes.
     *
     * @return the detected type, or null when the content is not a supported image
     */
    public static ImageType detectImageType(byte[] data) {
        if (data == null || data.length < 12) {
            // SVG can be tiny text; handle below after the binary checks.
            return detectSvg(data);
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return ImageType.PNG;
        }
        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return ImageType.JPEG;
        }
        // GIF: "GIF87a" / "GIF89a"
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && (data[3] == '8') && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return ImageType.GIF;
        }
        // WEBP: "RIFF" .... "WEBP"
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return ImageType.WEBP;
        }
        // SVG: text content containing an <svg element
        return detectSvg(data);
    }

    private static ImageType detectSvg(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        String head = new String(data, 0, Math.min(data.length, 2048), java.nio.charset.StandardCharsets.UTF_8);
        // Strip BOM first (not a whitespace char), then leading whitespace
        int start = 0;
        if (head.startsWith("\uFEFF")) {
            start = 1;
        }
        while (start < head.length() && Character.isWhitespace(head.charAt(start))) {
            start++;
        }
        String trimmed = head.substring(start).toLowerCase(Locale.ROOT);
        return trimmed.contains("<svg") ? ImageType.SVG : null;
    }

    /**
     * Store image bytes under the project directory with an unguessable UUID name.
     *
     * @param projectSlug URL-friendly project slug (validated)
     * @param data        raw image bytes (type must already be detected by the caller)
     * @param type        detected image type — determines the stored extension
     * @return the stored filename ({uuid}.{ext}) relative to the project directory
     */
    public String store(String projectSlug, byte[] data, ImageType type) throws IOException {
        if (!PROJECT_SLUG.matcher(projectSlug).matches()) {
            throw new BadRequestException("Invalid project slug");
        }
        Path projectDir = rootDir.resolve(projectSlug);
        Files.createDirectories(projectDir);

        String storedName = UUID.randomUUID().toString() + "." + type.getExtension();
        Path target = resolveStoredPath(projectSlug, storedName);
        Files.write(target, data);
        log.info("Stored image {} ({} bytes) for project {}", storedName, data.length, projectSlug);
        return storedName;
    }

    /**
     * Store image bytes under a caller-provided stored filename (WIKI4AI-74 first-run seed).
     *
     * <p>Unlike {@link #store(String, byte[], ImageType)} the filename is NOT generated:
     * the caller passes an exact {uuid}.{ext} name so that markdown references in the
     * seeded documents keep working without any content rewriting. The same strict
     * validation as {@link #load} applies (lowercase UUID + allowed extension), so this
     * method cannot be abused for path traversal.</p>
     *
     * @param projectSlug URL-friendly project slug (validated)
     * @param storedName  exact stored filename ({uuid}.{ext}, validated)
     * @param data        raw image bytes
     * @return the stored filename (identical to the input)
     */
    public String storeWithFixedName(String projectSlug, String storedName, byte[] data) throws IOException {
        if (!PROJECT_SLUG.matcher(projectSlug).matches()) {
            throw new BadRequestException("Invalid project slug");
        }
        if (storedName == null || !STORED_FILENAME.matcher(storedName).matches()) {
            throw new BadRequestException("Invalid image filename");
        }
        Path projectDir = rootDir.resolve(projectSlug);
        Files.createDirectories(projectDir);
        // Overwrite: seeded content is deterministic, and a re-run on the same fresh
        // instance (e.g. after a mid-seed crash) must converge to the same state.
        Path target = resolveStoredPath(projectSlug, storedName);
        Files.write(target, data);
        log.info("Stored image {} ({} bytes) for project {}", storedName, data.length, projectSlug);
        return storedName;
    }

    /**
     * Load a stored image. The filename must match the strict stored-filename
     * pattern; any other value is rejected with IllegalArgumentException (400).
     *
     * @throws java.nio.file.NoSuchFileException when the file does not exist (404)
     */
    public StoredImage load(String projectSlug, String filename) throws IOException {
        if (!PROJECT_SLUG.matcher(projectSlug).matches()) {
            throw new BadRequestException("Invalid project slug");
        }
        if (filename == null || !STORED_FILENAME.matcher(filename).matches()) {
            throw new BadRequestException("Invalid image filename");
        }
        Path target = resolveStoredPath(projectSlug, filename);
        byte[] data = Files.readAllBytes(target); // throws NoSuchFileException when missing
        ImageType type = detectImageType(data);
        if (type == null) {
            // Corrupted / unsupported content on disk — treat as not found.
            throw new java.nio.file.NoSuchFileException("Unsupported stored image: " + filename);
        }
        return new StoredImage(data, type);
    }

    /**
     * Resolve the absolute path for a project+filename pair and verify it stays
     * inside the storage root (defense in depth on top of the pattern checks).
     */
    private Path resolveStoredPath(String projectSlug, String filename) {
        Path target = rootDir.resolve(projectSlug).resolve(filename).normalize();
        if (!target.startsWith(rootDir)) {
            throw new BadRequestException("Invalid image path");
        }
        return target;
    }

    /** Exposed for tests. */
    public Path getRootDir() {
        return rootDir;
    }
}
