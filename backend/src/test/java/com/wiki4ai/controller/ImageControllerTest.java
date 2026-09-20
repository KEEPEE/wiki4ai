package com.wiki4ai.controller;

import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.service.ImageStorageService;
import com.wiki4ai.service.ImageStorageService.ImageType;
import com.wiki4ai.service.ImageStorageService.StoredImage;
import com.wiki4ai.service.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.NoSuchFileException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC tests for ImageController (WIKI4AI-64).
 * Controller layer in isolation — security disabled here; the login-only 401
 * behaviour is covered by SecurityConfigTest with security.enabled=true.
 */
@WebMvcTest(ImageController.class)
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(exclude = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
class ImageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ImageStorageService imageStorageService;

    @MockBean
    private ProjectService projectService;

    private static final String PROJECT_SLUG = "my-project";
    private static final String STORED_NAME = UUID.randomUUID().toString() + ".png";

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

    private ProjectDTO project() {
        return ProjectDTO.builder().id(1L).name("My Project").slug(PROJECT_SLUG).build();
    }

    @Nested
    @DisplayName("POST /api/v1/images/{projectSlug} — upload")
    class UploadTests {

        @Test
        @DisplayName("Should return 201 with url, markdown snippet and metadata for a valid PNG")
        void shouldUploadValidPng() throws Exception {
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());
            given(imageStorageService.store(eq(PROJECT_SLUG), any(byte[].class), eq(ImageType.PNG)))
                    .willReturn(STORED_NAME);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "screenshot.png", "image/png", pngBytes());

            mockMvc.perform(multipart("/api/v1/images/" + PROJECT_SLUG).file(file))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.url").value("/images/" + PROJECT_SLUG + "/" + STORED_NAME))
                    .andExpect(jsonPath("$.markdown").value("![screenshot](/images/" + PROJECT_SLUG + "/" + STORED_NAME + ")"))
                    .andExpect(jsonPath("$.filename").value("screenshot.png"))
                    .andExpect(jsonPath("$.storedName").value(STORED_NAME))
                    .andExpect(jsonPath("$.size").value(pngBytes().length))
                    .andExpect(jsonPath("$.contentType").value("image/png"));
        }

        @Test
        @DisplayName("Should return 400 for an empty file")
        void shouldRejectEmptyFile() throws Exception {
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());

            MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

            mockMvc.perform(multipart("/api/v1/images/" + PROJECT_SLUG).file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("empty")));
        }

        @Test
        @DisplayName("Should return 400 for unsupported content (text disguised as image)")
        void shouldRejectUnsupportedType() throws Exception {
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());

            MockMultipartFile file = new MockMultipartFile(
                    "file", "notes.txt", "image/png", "# not an image".getBytes());

            mockMvc.perform(multipart("/api/v1/images/" + PROJECT_SLUG).file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid file format")));
        }

        @Test
        @DisplayName("Should return 413 when the file exceeds 10 MB")
        void shouldRejectOversizedFile() throws Exception {
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());

            byte[] big = new byte[(int) (ImageController.MAX_IMAGE_SIZE_BYTES + 1)];
            // PNG magic header so it would pass type detection if size were not enforced
            big[0] = (byte) 0x89; big[1] = 0x50; big[2] = 0x4E; big[3] = 0x47;
            big[4] = 0x0D; big[5] = 0x0A; big[6] = 0x1A; big[7] = 0x0A;

            MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

            mockMvc.perform(multipart("/api/v1/images/" + PROJECT_SLUG).file(file))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("10MB")));
        }

        @Test
        @DisplayName("Should return 404 when the project does not exist")
        void shouldReturn404ForUnknownProject() throws Exception {
            willThrow(new EntityNotFoundException("Project not found with slug: no-such-project"))
                    .given(projectService).getProjectBySlug("no-such-project");

            MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", pngBytes());

            mockMvc.perform(multipart("/api/v1/images/no-such-project").file(file))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/images/{projectSlug}/{filename} — authenticated read")
    class ReadTests {

        @Test
        @DisplayName("Should return 200 with image bytes and correct Content-Type")
        void shouldServeImage() throws Exception {
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());
            given(imageStorageService.load(eq(PROJECT_SLUG), eq(STORED_NAME)))
                    .willReturn(new StoredImage(pngBytes(), ImageType.PNG));

            mockMvc.perform(get("/api/v1/images/" + PROJECT_SLUG + "/" + STORED_NAME))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(content().bytes(pngBytes()))
                    .andExpect(header().string("Cache-Control", "private, max-age=3600"));
        }

        @Test
        @DisplayName("Should serve SVG with image/svg+xml and a restrictive CSP header")
        void shouldServeSvgWithCsp() throws Exception {
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());
            byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes();
            String storedSvg = UUID.randomUUID().toString() + ".svg";
            given(imageStorageService.load(eq(PROJECT_SLUG), eq(storedSvg)))
                    .willReturn(new StoredImage(svg, ImageType.SVG));

            mockMvc.perform(get("/api/v1/images/" + PROJECT_SLUG + "/" + storedSvg))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/svg+xml"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'"));
        }

        @Test
        @DisplayName("Should return 404 when the image does not exist")
        void shouldReturn404WhenMissing() throws Exception {
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());
            willThrow(new NoSuchFileException(STORED_NAME))
                    .given(imageStorageService).load(eq(PROJECT_SLUG), eq(STORED_NAME));

            mockMvc.perform(get("/api/v1/images/" + PROJECT_SLUG + "/" + STORED_NAME))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 for filenames outside the strict stored-name pattern")
        void shouldRejectInvalidFilename() throws Exception {
            // The storage layer rejects anything that is not a lowercase UUID +
            // allowed extension (this is what blocks path traversal); the
            // controller surfaces that as 400. Traversal strings themselves are
            // covered by ImageStorageServiceTest#rejectsBadFilenames.
            given(projectService.getProjectBySlug(PROJECT_SLUG)).willReturn(project());
            willThrow(new BadRequestException("Invalid image filename"))
                    .given(imageStorageService).load(eq(PROJECT_SLUG), eq("not-a-valid-uuid.png"));

            mockMvc.perform(get("/api/v1/images/" + PROJECT_SLUG + "/not-a-valid-uuid.png"))
                    .andExpect(status().isBadRequest());
        }
    }
}
