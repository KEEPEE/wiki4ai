package com.wiki4ai.controller;

import com.wiki4ai.service.ImageStorageService;
import com.wiki4ai.service.ImageStorageService.ImageType;
import com.wiki4ai.service.ImageStorageService.StoredImage;
import com.wiki4ai.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for image upload and authenticated serving (WIKI4AI-64).
 *
 * Security model (hard user decision — same policy as global search):
 * NO anonymous access to uploaded images. Both endpoints require a valid JWT:
 * the paths live under /api/v1/images/**, which is not covered by any
 * permitAll rule in SecurityConfig (note: GET /api/v1/projects/** IS public —
 * that is why images are NOT nested under the project path).
 *
 * The markdown stored in documents uses relative URLs of the form
 * /images/{projectSlug}/{uuid}.{ext}. A browser &lt;img src&gt; cannot send a
 * Bearer header, so the WebUI intercepts such sources and fetches them from
 * this GET endpoint with the JWT, converting the response to a blob URL.
 */
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
@Tag(name = "Images", description = "API pre upload a autentizované servírovanie obrazkov")
public class ImageController {

    /** Maximum accepted image size: 10 MB (WIKI4AI-64). */
    public static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;

    private final ImageStorageService imageStorageService;
    private final ProjectService projectService;

    @Operation(summary = "Upload obrazku", description =
            "Nahraje obrazok (PNG/JPEG/WebP/GIF/SVG, max 10 MB) do projektu. MIME typ je detekovaný z magic bytes. " +
            "Vráti relatívnu URL /images/{projectSlug}/{uuid}.{ext} a markdown snippet pre vloženie do dokumentu.")
    @ApiResponse(responseCode = "201", description = "Obrazok úspešne nahraný")
    @ApiResponse(responseCode = "400", description = "Neplatný formát (nie je podporovaný obrazok) alebo prázdny súbor")
    @ApiResponse(responseCode = "404", description = "Projekt nebol nájdený")
    @ApiResponse(responseCode = "413", description = "Súbor je príliš veľký (max 10 MB)")
    @PostMapping(value = "/{projectSlug}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadImage(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Obrazkový súbor (PNG/JPEG/WebP/GIF/SVG)") @RequestParam("file") MultipartFile file)
            throws IOException {

        // Project must exist (404 via EntityNotFoundException handler)
        projectService.getProjectBySlug(projectSlug);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Invalid file format: uploaded file is empty.");
        }

        byte[] data = file.getBytes();
        if (data.length > MAX_IMAGE_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "timestamp", java.time.LocalDateTime.now().toString(),
                    "status", HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "error", "Payload Too Large",
                    "message", "Image size exceeds the maximum allowed size of 10MB"));
        }

        ImageType type = ImageStorageService.detectImageType(data);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Invalid file format. Only PNG, JPEG, WebP, GIF and SVG images are allowed.");
        }

        String storedName = imageStorageService.store(projectSlug, data, type);
        String url = "/images/" + projectSlug + "/" + storedName;

        // Alt text: original filename without extension/newlines (safe for markdown)
        String altText = sanitizeAltText(file.getOriginalFilename());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("markdown", "!" + altText + "(" + url + ")");
        body.put("filename", file.getOriginalFilename());
        body.put("storedName", storedName);
        body.put("size", data.length);
        body.put("contentType", type.getContentType());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "Načítanie nahraného obrazku", description =
            "Servíruje nahraný obrazok. Vyžaduje platný JWT (žiadny anonymný prístup). " +
            "WebUI volá tento endpoint fetch() s Bearer tokenom a konvertuje odpoveď na blob URL.")
    @ApiResponse(responseCode = "200", description = "Obsah obrazku")
    @ApiResponse(responseCode = "400", description = "Neplatný tvar filename (path traversal guard)")
    @ApiResponse(responseCode = "404", description = "Projekt alebo obrazok nebol nájdený")
    @GetMapping("/{projectSlug}/{filename}")
    public ResponseEntity<byte[]> getImage(
            @Parameter(description = "Slug projektu") @PathVariable String projectSlug,
            @Parameter(description = "Názov nahraného súboru ({uuid}.{ext})") @PathVariable String filename)
            throws IOException {

        // Project must exist (404 via EntityNotFoundException handler)
        projectService.getProjectBySlug(projectSlug);

        StoredImage image;
        try {
            image = imageStorageService.load(projectSlug, filename);
        } catch (NoSuchFileException e) {
            throw new jakarta.persistence.EntityNotFoundException(
                    "Image not found: " + projectSlug + "/" + filename);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(image.type().getContentType()));
        headers.setCacheControl("private, max-age=3600");
        headers.add("X-Content-Type-Options", "nosniff");
        if (image.type() == ImageType.SVG) {
            // SVG can embed scripts; neutralize any that slip through rendering.
            headers.add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        }
        return ResponseEntity.ok().headers(headers).body(image.data());
    }

    /**
     * Build a safe alt text from the original filename: strip extension,
     * newlines and markdown-breaking characters.
     */
    static String sanitizeAltText(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        int lastDot = originalFilename.lastIndexOf('.');
        String base = lastDot > 0 ? originalFilename.substring(0, lastDot) : originalFilename;
        // Remove newlines and characters that would break the markdown image syntax
        return base.replaceAll("[\\r\\n()]", " ").trim();
    }
}
