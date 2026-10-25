package com.wiki4ai.controller;

import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.CommentContractException;
import com.wiki4ai.exception.DocumentVersionConflictException;
import com.wiki4ai.exception.RateLimitExceededException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WIKI4AI-171: an unmapped path answered 500, and the answer to "that URL does not
 * exist" is 404.
 * <p>
 * Measured live on dev before this fix:
 * <pre>
 * GET http://localhost:3016/api/v1/auth/whoami
 * -&gt; 500 {"message":"No static resource api/v1/auth/whoami."}
 * </pre>
 * The cause is inside this advice, not in a controller: Spring 6.1 routes an unmapped
 * path to the default {@code /**} resource handler, which finds no resource and throws
 * {@link NoResourceFoundException}. The advice had no handler for that class, so the
 * exception fell through to {@code @ExceptionHandler(Exception.class)} and the client was
 * told the server had broken. The fix is one handler for one concrete exception class.
 * <p>
 * <h2>What this class is careful about</h2>
 * <ul>
 *   <li><b>The existing mappings are the point of the test, not an accident of it.</b>
 *   {@link ExistingMappingsAreUnchanged} enumerates every {@code @ExceptionHandler} the
 *   advice declares and asserts the status each one answers. A new handler that swallowed
 *   the 409 of {@code IllegalArgumentException}, or the 429 of
 *   {@code RateLimitExceededException}, fails here even if every 404/405 test passes.</li>
 *   <li><b>500 must survive.</b> A fix that turns every unexpected exception into a 404
 *   would make this class green and the application undebuggable.
 *   {@link ARealInternalErrorIsStill500} asserts the catch-all still answers 500 for a
 *   genuine fault.</li>
 *   <li><b>The resource mappings stay on.</b> The tempting one-line fix —
 *   {@code spring.web.resources.add-mappings=false} — was measured and rejected, because
 *   this backend serves springdoc's swagger-ui through Spring's resource handling.
 *   {@link ResourceServedEndpointsStillAnswer} is the proof that nothing was taken away:
 *   swagger-ui, the OpenAPI document and the actuator health endpoint all still answer.
 *   {@link #addMappingsIsStillOn()} states the switch itself.</li>
 *   <li><b>No filter was touched.</b> {@code JwtAuthenticationFilter} runs before the
 *   {@code DispatcherServlet}, so a {@code @RestControllerAdvice} cannot see anything it
 *   throws; that is why the fix lives in the advice and handles only exceptions thrown
 *   <i>inside</i> the dispatch.</li>
 * </ul>
 * Rule 5b: this class writes nothing to the database — every request here is answered
 * before any service touches a row — so it needs no {@code cleanState()}, and it carries
 * no {@code @DirtiesContext} for the reason the SPEC states (the suite shares one named
 * in-memory H2 and closing a context drops the schema the other contexts are using).
 * <p>
 * In this profile {@code security.enabled=false}, and {@code AuthController} is
 * {@code @ConditionalOnProperty(name = "security.enabled", havingValue = "true")} — so the
 * whole controller is absent here and {@code /api/v1/auth/**} is unmapped for a reason
 * unrelated to this story. The same requests are therefore asserted a second time in
 * {@link HttpStatusFallbackSecurityChainTest}, with security on, the controller loaded and
 * the real {@code JwtAuthenticationFilter} in the chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("HTTP status fallback (WIKI4AI-171)")
class HttpStatusFallbackTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * The switch this story deliberately did NOT use. Asserted rather than assumed: if a
     * later commit turns the resource mappings off, the 404 tests here stay green while
     * swagger-ui disappears — this is the assertion that stops that from being silent.
     */
    @Value("${spring.web.resources.add-mappings:true}")
    private boolean addMappings;

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    // ── AC: an unmapped path is 404 ───────────────────────────────────────────

    @Nested
    @DisplayName("AC: a path no controller maps is 404, and not 500")
    class UnmappedPathIsNotFound {

        /**
         * The exact request measured live on dev. {@code /api/v1/auth/**} is permitAll in
         * the untouched SecurityConfig, so the request reaches the DispatcherServlet and
         * what answers it is the advice — which is the thing under test.
         */
        @Test
        @DisplayName("GET /api/v1/auth/whoami → 404 (was 500)")
        void whoamiUnderAuthIs404() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/auth/whoami")).andReturn();

            assertThat(statusOf(result))
                    .as("the measured case: an unmapped path answered 500")
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(statusOf(result)).as("500 must not be the answer to 'no such URL'")
                    .isLessThan(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }

        @Test
        @DisplayName("the 404 says which endpoint is missing, not 'No static resource'")
        void bodyNamesTheMissingEndpoint() throws Exception {
            mockMvc.perform(get("/api/v1/auth/whoami"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.path").value("api/v1/auth/whoami"))
                    .andExpect(jsonPath("$.message").value("No endpoint GET api/v1/auth/whoami"));
        }

        /**
         * The same case under a prefix the API does map, so the 404 cannot be an artefact
         * of one controller: {@code /api/v1/work-items/**} exists, {@code nope/nope} under
         * it does not.
         */
        @Test
        @DisplayName("GET /api/v1/work-items/nope/nope → 404")
        void unmappedPathUnderAMappedPrefixIs404() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/work-items/nope/nope")).andReturn();

            assertThat(statusOf(result)).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(statusOf(result)).isBetween(400, 499);
        }

        @Test
        @DisplayName("a deeper unmapped path is 404 too, whatever the depth")
        void deepUnmappedPathIs404() throws Exception {
            mockMvc.perform(get("/api/v1/definitely/not/mapped/at/all"))
                    .andExpect(status().isNotFound())
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("an unmapped path on an unsafe method is 404 too, not 500")
        void unmappedPathWithUnsafeMethodIs404() throws Exception {
            MvcResult result = mockMvc.perform(delete("/api/v1/auth/whoami")).andReturn();

            assertThat(statusOf(result))
                    .as("no mapping matches at all, so there is no method to be wrong about")
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }

        /**
         * Which exception class actually reaches the advice. The message is the evidence:
         * {@code NoResourceFoundException} is what Spring 6.1 throws here, so that is the
         * class the handler is routed to — and {@code NoHandlerFoundException} is
         * <b>not</b> added to the advice, because with the resource mappings on it is
         * never thrown: the {@code /**} registration matches every path.
         */
        @Test
        @DisplayName("measured: the unmapped path is answered by NoResourceFoundException")
        void theExceptionIsNoResourceFoundException() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/auth/whoami")).andReturn();
            String message = String.valueOf(jsonPathMessage(result));

            assertThat(message)
                    .as("the advice answered, and it answered the resource-handler exception — "
                            + "the old body was 'No static resource api/v1/auth/whoami.'")
                    .contains("api/v1/auth/whoami");
            assertThat(result.getResolvedException())
                    .as("the exception the DispatcherServlet handed to the advice")
                    .isInstanceOf(NoResourceFoundException.class);
        }

        private Object jsonPathMessage(MvcResult result) throws Exception {
            return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.message");
        }
    }

    // ── AC: the wrong method on a mapped path is 405 ──────────────────────────

    @Nested
    @DisplayName("AC: the wrong method on a mapped path is 405, and not 500")
    class WrongMethodIsMethodNotAllowed {

        /**
         * {@code /api/v1/projects} is mapped for GET and POST and for nothing else, so
         * DELETE has a path and no method: {@code HttpRequestMethodNotSupportedException}.
         */
        @Test
        @DisplayName("DELETE /api/v1/projects → 405 with an Allow header")
        void deleteOnGetAndPostOnlyPathIs405() throws Exception {
            MvcResult result = mockMvc.perform(delete("/api/v1/projects")).andReturn();

            assertThat(statusOf(result))
                    .as("the path exists, the method does not")
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
            assertThat(statusOf(result)).isLessThan(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(result.getResponse().getHeader("Allow"))
                    .as("the caller is told which methods the path does answer")
                    .isNotBlank();
        }

        @Test
        @DisplayName("PUT /api/v1/projects → 405 (the path answers GET and POST)")
        void putOnProjectsIs405() throws Exception {
            MvcResult result = mockMvc.perform(put("/api/v1/projects")).andReturn();

            assertThat(statusOf(result)).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        }

        @Test
        @DisplayName("DELETE /api/v1/work-items/1/events → 405 (the timeline is GET only)")
        void deleteOnTimelineIs405() throws Exception {
            MvcResult result = mockMvc.perform(delete("/api/v1/work-items/1/events")).andReturn();

            assertThat(statusOf(result))
                    .as("a mapped path with a GET-only method")
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        }
    }

    // ── AC: 500 is still the answer to a real internal error ──────────────────

    @Nested
    @DisplayName("AC: a real internal error is still 500")
    class ARealInternalErrorIsStill500 {

        /**
         * Standalone MockMvc, on purpose: it runs the real {@link GlobalExceptionHandler}
         * over a controller that throws, without adding a test-only controller bean to the
         * shared application context (component scanning would have picked such a nested
         * class up in every other context of this suite).
         */
        @Test
        @DisplayName("a controller that throws is still 500 — the catch-all was not widened")
        void genuineFaultIsStill500() {
            MockMvc standalone = MockMvcBuilders
                    .standaloneSetup(new FaultyController())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

            try {
                MvcResult result = standalone.perform(get("/__test__/boom")).andReturn();

                assertThat(result.getResolvedException())
                        .as("the fault reached the advice, which is what the catch-all answers")
                        .isInstanceOf(IllegalStateException.class);
                assertThat(statusOf(result))
                        .as("the new 404 and the existing 405 are routed by concrete exception "
                                + "classes; an ordinary runtime fault still lands on the "
                                + "Exception handler")
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
                assertThat(result.getResponse().getContentAsString()).contains("simulated internal failure");
            } catch (Exception e) {
                throw new AssertionError("the standalone MockMvc should not propagate the fault", e);
            }
        }

        @Test
        @DisplayName("the advice's own catch-all answers 500 for an unexpected exception")
        void catchAllAnswers500() {
            assertThat(new GlobalExceptionHandler()
                    .handleGeneralException(new IllegalStateException("unexpected"))
                    .getStatusCode()
                    .value())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }

        /**
         * Only used by {@link #genuineFaultIsStill500()}; never a bean of any context.
         * <p>
         * A <b>non-static</b> inner class on purpose. It sits inside
         * {@code com.wiki4ai.controller}, which every {@code @SpringBootTest} of this suite
         * component-scans, and {@code @RestController} is a stereotype — a static nested
         * class carrying one is an independent candidate component and would register a
         * test endpoint in contexts that are not this one. A non-static inner class is not
         * independent, so the scanner skips it, while {@code standaloneSetup} still sees a
         * handler.
         */
        @org.springframework.web.bind.annotation.RestController
        class FaultyController {
            @org.springframework.web.bind.annotation.GetMapping("/__test__/boom")
            public String boom() {
                throw new IllegalStateException("simulated internal failure");
            }
        }
    }

    // ── Past A: the resource mappings were left on, and nothing they serve broke ──

    @Nested
    @DisplayName("Past A: add-mappings stayed on, and everything it serves still answers")
    class ResourceServedEndpointsStillAnswer {

        @Test
        @DisplayName("spring.web.resources.add-mappings is still true")
        void addMappingsIsStillOn() {
            assertThat(addMappings)
                    .as("the one-line fix was rejected: this backend serves swagger-ui through "
                            + "Spring's resource handling, and switching the mappings off would "
                            + "remove it")
                    .isTrue();
        }

        @Test
        @DisplayName("GET /swagger-ui.html still answers (springdoc is not collateral damage)")
        void swaggerUiStillAnswers() throws Exception {
            MvcResult result = mockMvc.perform(get("/swagger-ui.html")).andReturn();

            assertThat(statusOf(result))
                    .as("springdoc's swagger-ui is served through resource handling; 404 or 500 here "
                            + "would mean the mappings were switched off")
                    .isNotEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(statusOf(result))
                    .as("springdoc redirects /swagger-ui.html to /swagger-ui/index.html")
                    .isBetween(200, 399);
        }

        @Test
        @DisplayName("GET /swagger-ui/index.html still answers 200")
        void swaggerUiIndexStillAnswers() throws Exception {
            MvcResult result = mockMvc.perform(get("/swagger-ui/index.html")).andReturn();

            assertThat(statusOf(result)).isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("GET /v3/api-docs still answers 200 with an OpenAPI document")
        void openApiDocumentStillAnswers() throws Exception {
            MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();

            assertThat(statusOf(result)).isEqualTo(HttpStatus.OK.value());
            assertThat(result.getResponse().getContentAsString()).contains("\"openapi\"");
        }

        @Test
        @DisplayName("GET /actuator/health still answers 200")
        void actuatorHealthStillAnswers() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("GET /api/v1/projects still answers 200 (a mapped path is untouched)")
        void mappedProjectListStillAnswers() throws Exception {
            assertThat(statusOf(mockMvc.perform(get("/api/v1/projects")).andReturn()))
                    .isEqualTo(HttpStatus.OK.value());
        }
    }

    // ── The insurance: no existing mapping moved ──────────────────────────────

    @Nested
    @DisplayName("Insurance: every mapping the advice declares, at the status it answers")
    class ExistingMappingsAreUnchanged {

        /**
         * The handler set, read off the advice. This is the assertion that catches a
         * handler being <i>removed</i> or <i>re-routed</i>, which a per-status test cannot
         * see on its own: WIKI4AI-171 adds exactly one handler, for exactly one new
         * exception class, and nothing else may change.
         */
        @Test
        @DisplayName("the advice routes exactly these exception classes — one added, none moved")
        void handlerSetIsExactlyWhatItWasPlusTheNewOne() {
            Set<Class<? extends Throwable>> routed = new LinkedHashSet<>();
            for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
                ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
                if (annotation != null) {
                    routed.addAll(Arrays.asList(annotation.value()));
                }
            }

            assertThat(routed)
                    .as("every class the advice answered before WIKI4AI-171 is still answered, "
                            + "and NoResourceFoundException is the only addition")
                    .contains(
                            MethodArgumentNotValidException.class,
                            EntityNotFoundException.class,
                            AccessDeniedException.class,
                            com.wiki4ai.exception.RegistrationDisabledException.class,
                            com.wiki4ai.exception.SetupAlreadyCompletedException.class,
                            HttpRequestMethodNotSupportedException.class,
                            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
                            com.wiki4ai.exception.ContentEditException.class,
                            DocumentVersionConflictException.class,
                            jakarta.persistence.OptimisticLockException.class,
                            org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                            BadRequestException.class,
                            IllegalArgumentException.class,
                            org.springframework.web.multipart.MaxUploadSizeExceededException.class,
                            org.springframework.web.multipart.MultipartException.class,
                            RateLimitExceededException.class,
                            CommentContractException.class,
                            Exception.class,
                            NoResourceFoundException.class);
        }

        @Test
        @DisplayName("IllegalArgumentException is still 409 — not 400, not 404")
        void illegalArgumentIsStill409() {
            assertThat(statusCode(new GlobalExceptionHandler().handleIllegalArgument(
                    new IllegalArgumentException("A project with that slug already exists"))))
                    .isEqualTo(HttpStatus.CONFLICT.value());
        }

        @Test
        @DisplayName("EntityNotFoundException is still 404")
        void entityNotFoundIsStill404() {
            assertThat(statusCode(new GlobalExceptionHandler().handleEntityNotFound(
                    new EntityNotFoundException("Work item not found with id: 42"))))
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }

        @Test
        @DisplayName("AccessDeniedException is still 403")
        void accessDeniedIsStill403() {
            assertThat(statusCode(new GlobalExceptionHandler().handleAccessDenied(
                    new AccessDeniedException("Not your private document"))))
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @Test
        @DisplayName("BadRequestException is still 400")
        void badRequestIsStill400() {
            assertThat(statusCode(new GlobalExceptionHandler().handleBadRequest(
                    new BadRequestException("slots must be a number"))))
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        @DisplayName("CommentContractException is still 422")
        void commentContractIsStill422() {
            assertThat(statusCode(new GlobalExceptionHandler().handleCommentContract(
                    new CommentContractException("result", List.of("'where' is required")))))
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        }

        @Test
        @DisplayName("RateLimitExceededException is still 429 with Retry-After")
        void rateLimitIsStill429WithRetryAfter() {
            ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler()
                    .handleRateLimitExceeded(new RateLimitExceededException("too many requests", 7L));

            assertThat(statusCode(response)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(response.getHeaders().getFirst("Retry-After"))
                    .as("the 429 without its header is a different contract")
                    .isEqualTo("7");
        }

        @Test
        @DisplayName("DocumentVersionConflictException is still 409")
        void versionConflictIsStill409() {
            assertThat(statusCode(new GlobalExceptionHandler().handleDocumentVersionConflict(
                    new DocumentVersionConflictException(9L, 7L))))
                    .isEqualTo(HttpStatus.CONFLICT.value());
        }

        @Test
        @DisplayName("MethodArgumentNotValidException is still 400")
        void beanValidationIsStill400() throws Exception {
            Method method = SampleBody.class.getDeclaredMethod("body", String.class);
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new SampleBody(), "sample");
            binding.addError(new FieldError("sample", "body", "must not be blank"));

            assertThat(statusCode(new GlobalExceptionHandler().handleValidationExceptions(
                    new MethodArgumentNotValidException(new MethodParameter(method, 0), binding))))
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        @DisplayName("HttpRequestMethodNotSupportedException is still 405 (it was mapped before this story)")
        void methodNotSupportedIsStill405() {
            assertThat(statusCode(new GlobalExceptionHandler().handleMethodNotSupported(
                    new HttpRequestMethodNotSupportedException("DELETE"))))
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        }

        @Test
        @DisplayName("the new handler answers 404 and is routed by a concrete class only")
        void newHandlerIs404AndSpecific() throws Exception {
            ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler()
                    .handleNoResourceFound(new NoResourceFoundException(
                            org.springframework.http.HttpMethod.GET, "api/v1/auth/whoami"));

            assertThat(statusCode(response)).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(response.getBody()).containsEntry("status", 404);

            assertThat(ServletException.class.isAssignableFrom(NoResourceFoundException.class))
                    .as("NoResourceFoundException is a ServletException, so it is not a subclass of "
                            + "anything an existing handler answers — no existing mapping can absorb "
                            + "it, and it cannot absorb an existing one")
                    .isTrue();
            assertThat(EntityNotFoundException.class.isAssignableFrom(NoResourceFoundException.class))
                    .isFalse();
            assertThat(IllegalArgumentException.class.isAssignableFrom(NoResourceFoundException.class))
                    .isFalse();
        }

        /** Only needed to build a real MethodArgumentNotValidException. */
        private static class SampleBody {
            @SuppressWarnings("unused")
            void body(String value) {
            }
        }
    }

    private static int statusCode(ResponseEntity<Map<String, Object>> response) {
        return response.getStatusCode().value();
    }
}
