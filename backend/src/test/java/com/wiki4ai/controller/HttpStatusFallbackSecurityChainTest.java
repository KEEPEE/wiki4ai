package com.wiki4ai.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * WIKI4AI-171, measured in the configuration dev actually runs.
 * <p>
 * {@link HttpStatusFallbackTest} runs in the plain test profile, where
 * {@code security.enabled=false} — and {@code AuthController} is
 * {@code @ConditionalOnProperty(name = "security.enabled", havingValue = "true")}, so in
 * that profile the whole controller is absent and {@code /api/v1/auth/**} is unmapped for
 * a reason that has nothing to do with this story. Measured there:
 * {@code GET /api/v1/auth/health} is 404 because the controller is not loaded at all.
 * <p>
 * The request measured live on dev was made against an instance with security on:
 * {@code AuthController} loaded, {@code JwtAuthenticationFilter} in the chain, and
 * {@code /api/v1/auth/**} permitAll. This class reproduces exactly that — one property,
 * {@code security.enabled=true}, the same pattern {@code ActorWhoamiIntegrationTest}
 * uses — so the 404 and the 405 are asserted against the real controller set and the real
 * filter chain, not against a stripped-down one.
 * <p>
 * It is a separate class rather than a {@code @Nested} one because a
 * {@code @TestPropertySource} on a nested class does not change the context: measured, the
 * nested variant inherited the outer context unchanged and every assertion here came back
 * 404. A separate class is what produces a separate, cached context.
 * <p>
 * No {@code @DirtiesContext} — the suite shares one named in-memory H2 and closing a
 * context drops the schema the others are using. This class writes no rows: every request
 * here is answered by the filter chain or by the advice, before any service touches data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
@DisplayName("HTTP status fallback with the real security chain (WIKI4AI-171)")
class HttpStatusFallbackSecurityChainTest {

    @Autowired
    private MockMvc mockMvc;

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /**
     * The request measured live on dev, in the configuration dev runs:
     * {@code GET …/api/v1/auth/whoami → 500 "No static resource api/v1/auth/whoami."}.
     * The path is unmapped because {@code AuthController} has no {@code whoami} method —
     * not because the controller is missing — and the request passes every filter because
     * {@code /api/v1/auth/**} is permitAll. What answers it is the advice.
     */
    @Test
    @DisplayName("GET /api/v1/auth/whoami → 404 through the real filter chain, not 500")
    void whoamiIs404ThroughTheFilterChain() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/whoami")).andReturn();

        assertThat(statusOf(result)).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(statusOf(result)).isLessThan(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(result.getResolvedException())
                .as("the exception the DispatcherServlet handed to the advice")
                .isInstanceOf(NoResourceFoundException.class);
    }

    /**
     * The wrong-method case, on a path this configuration really maps:
     * {@code POST /api/v1/auth/login} exists, PUT does not.
     */
    @Test
    @DisplayName("PUT /api/v1/auth/login → 405: the controller is loaded, the method is not")
    void putOnLoginIs405() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/auth/login")).andReturn();

        assertThat(statusOf(result))
                .as("POST /api/v1/auth/login is mapped in this configuration")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(result.getResolvedException())
                .isInstanceOf(HttpRequestMethodNotSupportedException.class);
    }

    /** The control: a mapped permitAll path of the same controller is untouched. */
    @Test
    @DisplayName("GET /api/v1/auth/status is still 200")
    void mappedAuthPathStillAnswers() throws Exception {
        assertThat(statusOf(mockMvc.perform(get("/api/v1/auth/status")).andReturn()))
                .isEqualTo(HttpStatus.OK.value());
    }

    /**
     * Past B, stated as a measurement instead of a warning.
     * <p>
     * {@code JwtAuthenticationFilter} runs before the {@code DispatcherServlet}, so a
     * request that never gets past the filter chain never produces an exception the advice
     * could see. An unmapped path behind authentication is therefore answered 401 by the
     * entry point — and that is correct, not a routing bug: the caller is not allowed to
     * know whether the path exists. This is also why the fix lives in the advice and
     * handles only exceptions thrown inside the dispatch; a solution built in the filter
     * would not be seen by {@code @RestControllerAdvice} at all.
     */
    @Test
    @DisplayName("an unmapped path that requires auth is 401, not 404: the filter runs first")
    void unmappedProtectedPathIs401Not404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/work-items/nope/nope")).andReturn();

        assertThat(statusOf(result))
                .as("the entry point answered, before any handler lookup")
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(result.getResolvedException())
                .as("nothing reached the advice, which is the point")
                .isNull();
    }
}
