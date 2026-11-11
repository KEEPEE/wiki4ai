package com.wiki4ai.controller;

import com.wiki4ai.dto.AuthResponseDTO;
import com.wiki4ai.dto.LoginRequestDTO;
import com.wiki4ai.dto.ProfileUpdateRequestDTO;
import com.wiki4ai.dto.RegisterRequestDTO;
import com.wiki4ai.dto.SetupRequestDTO;
import com.wiki4ai.dto.TokenGenerationRequestDTO;
import com.wiki4ai.dto.TokenResponseDTO;
import com.wiki4ai.config.JwtAuthenticationFilter;
import com.wiki4ai.dto.UserDTO;
import com.wiki4ai.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for authentication endpoints.
 * Only loaded when security.enabled=true.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "User registration and login endpoints")
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * WIKI4AI-69: instance initialization status (public probe for the WebUI).
     * <p>
     * Returns only two booleans — deliberately no user details, counts or any other
     * information that could be used to fingerprint the instance:
     * <ul>
     *   <li>{@code initialized} — true once at least one account exists; while false
     *       the WebUI shows the first-run setup form instead of the login page;</li>
     *   <li>{@code registrationOpen} — whether {@code POST /api/v1/auth/register} is
     *       currently accepted (WIKI4AI-70 policy); the WebUI hides the register
     *       form/link when it is false.</li>
     * </ul>
     */
    @GetMapping("/status")
    @Operation(summary = "Instance auth status", description = "Public probe: whether the instance has been initialized (first account exists) and whether public registration is currently open. Returns booleans only — no user details.")
    @ApiResponse(responseCode = "200", description = "Status returned")
    public ResponseEntity<Map<String, Boolean>> status() {
        Map<String, Boolean> body = new LinkedHashMap<>();
        body.put("initialized", authService.isInstanceInitialized());
        body.put("registrationOpen", authService.isRegistrationOpen());
        return ResponseEntity.ok(body);
    }

    /**
     * WIKI4AI-69: first-run setup. Creates the very first account of an
     * uninitialized instance with the ADMIN role.
     * <p>
     * Public (no JWT), but only accepted while the users table is empty — after
     * the first account exists it returns 403 with a generic message. This is the
     * only way to initialize a fresh instance that was not provisioned via the
     * explicit ADMIN_INITIAL_USERNAME/ADMIN_INITIAL_PASSWORD bootstrap env vars.
     */
    @PostMapping("/setup")
    @Operation(summary = "First-run setup", description = "Creates the first ADMIN account. Only accepted while no account exists (empty users table); afterwards returns 403. Email is optional — when omitted {username}@localhost is derived.")
    @ApiResponse(responseCode = "201", description = "Initial ADMIN account created")
    @ApiResponse(responseCode = "403", description = "Instance already initialized (setup no longer available)")
    @ApiResponse(responseCode = "400", description = "Validation failed (missing/short username or password)")
    public ResponseEntity<?> setup(@Valid @RequestBody SetupRequestDTO request) {
        UserDTO user = authService.createInitialAdmin(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /**
     * Register a new user.
     * <p>
     * WIKI4AI-70: subject to the registration policy — by default open only while
     * no account exists yet, closed (403) afterwards; can be kept permanently open
     * via {@code auth.registration.open=true}.
     */
    @PostMapping("/register")
    @Operation(summary = "Register a new user", description = "Creates a new user account with the provided credentials. Closed by default once the first account exists (403); opt-in open registration via auth.registration.open=true")
    @ApiResponse(responseCode = "201", description = "User successfully registered")
    @ApiResponse(responseCode = "403", description = "Registration is disabled on this instance")
    @ApiResponse(responseCode = "409", description = "Username or email already exists")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDTO request) {
        try {
            UserDTO user = authService.registerUser(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }
    }

    /**
     * Login with username and password.
     */
    @PostMapping("/login")
    @Operation(summary = "Login", description = "Authenticates a user and returns access/refresh tokens")
    @ApiResponse(responseCode = "200", description = "Login successful, tokens returned")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDTO request) {
        AuthResponseDTO response;
        try {
            response = authService.loginUser(request);
        } catch (DataAccessException e) {
            // WIKI4AI-68: an infrastructure failure during login (DB unavailable, pool
            // exhaustion, constraint violation, ...) must not surface to the client as a
            // detailed 500. Log the real cause server-side and keep the generic message
            // for the client so unauthenticated callers learn nothing about DB state.
            log.error("Login failed due to database error for username='{}'", request.getUsername(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Invalid username or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        if (response == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Invalid username or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * The body {@code POST /api/v1/auth/refresh} accepts. Declared here rather than in
     * the dto package because it exists only for this endpoint, and the controller
     * already carries a local request record for {@code /token/named}.
     *
     * @param refreshToken the value the client stored at login. Not validated with
     *                     {@code @NotBlank}: a missing value is a 401 like every other
     *                     unusable credential, and bean validation would answer 400
     *                     with a field-error body the frontend cannot read.
     */
    public record RefreshRequest(String refreshToken) {}

    /**
     * Exchange a refresh token for a new access token.
     * <p>
     * WIKI4AI-197 (plan commit 2b). The frontend has called this path since it was
     * written ({@code apiClient.ts:43}, {@code AuthContext.tsx:240}) while the mapping
     * did not exist, so the call has been a 404 the whole time. It worked by accident:
     * a refresh token used to pass the access-token check, so sessions rode a 7-day
     * token. Now that an access token must say {@code type=ACCESS}, the session would
     * die at 24 hours unless this endpoint answers — hence 2 and 2b ship together.
     * <p>
     * Public (no bearer header required) because the credential being exchanged IS the
     * authentication. {@code /api/v1/auth/**} is already {@code permitAll}, and the
     * filter leaves a request with no {@code Authorization} header alone, so nothing in
     * the security configuration changes.
     * <p>
     * Every failure is one 401 with one message: malformed, wrong type, expired,
     * unknown or rotated-away row, account mismatch. Distinguishing them would let
     * anyone probe which refresh tokens a deployment knows about.
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh the access token",
            description = "Exchanges a valid REFRESH token (JWT plus its refresh_tokens row) for a new access token")
    @ApiResponse(responseCode = "200", description = "New access token issued")
    @ApiResponse(responseCode = "401", description = "The refresh token is missing, malformed, expired or unknown")
    public ResponseEntity<?> refresh(@RequestBody(required = false) RefreshRequest request) {
        AuthResponseDTO response;
        try {
            response = authService.refreshAccessToken(request == null ? null : request.refreshToken());
        } catch (DataAccessException e) {
            // Same reasoning as login: a database problem is not information an
            // unauthenticated caller should receive, and it must not become a 500.
            log.error("Refresh failed due to a database error", e);
            response = null;
        }

        if (response == null) {
            Map<String, String> error = new LinkedHashMap<>();
            error.put("error", "Invalid or expired refresh token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get the profile of the currently authenticated user.
     * Extracts username from SecurityContext (set by JwtAuthenticationFilter) and returns user info.
     */
    @GetMapping("/me")
    @Operation(summary = "Get current user profile", description = "Returns the profile of the currently authenticated user based on JWT token")
    @ApiResponse(responseCode = "200", description = "User profile returned successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    public ResponseEntity<?> getCurrentUserProfile() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // If no authentication is set, the user is not authenticated
        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Authentication required. Please provide a valid JWT token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        String username = authentication.getName();
        UserDTO profile = authService.getProfileByUsername(username);

        if (profile == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "User not found in database");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        return ResponseEntity.ok(profile);
    }

    /**
     * Update the profile of the currently authenticated user.
     * Allows updating username, email, and/or password.
     * All fields in the request body are optional — only provided fields are updated.
     * <p>
     * <b>WIKI4AI-202 — {@code @Valid} was missing here.</b> {@link ProfileUpdateRequestDTO}
     * declares {@code @Email} on {@code email}, {@code @Size(min = 3)} on
     * {@code newPassword} and {@code @Size(min = 2, max = 5)} on {@code language}, and the
     * contract below already advertises 400 for "validation failed" — but bean validation
     * only runs when the parameter is annotated, and this was the only request body in
     * this controller without it ({@code /setup}, {@code /register}, {@code /login} and
     * {@code /token/named} all have it). The constraints were therefore dead code:
     * {@code PUT /api/v1/auth/me {"email":"not-an-email"}} answered 200 and wrote the
     * malformed value to the account, and a one-character {@code newPassword} was accepted
     * as well. Found by {@code AuthControllerUpdateProfileIT}, a test class that had never
     * been executed (see its javadoc). Absent and empty values still pass — both
     * {@code @Email} and {@code @Size} treat them as valid — so the "all fields optional"
     * contract and the frontend's language-only {@code PUT} are unaffected.
     */
    @PutMapping("/me")
    @Operation(summary = "Update current user profile", description = "Updates the profile of the currently authenticated user. Optional fields: username, email, currentPassword (required for password change), newPassword")
    @ApiResponse(responseCode = "200", description = "Profile updated successfully")
    @ApiResponse(responseCode = "400", description = "Current password is incorrect or validation failed")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    @ApiResponse(responseCode = "409", description = "Username or email already taken by another user")
    public ResponseEntity<?> updateCurrentUserProfile(@Valid @RequestBody ProfileUpdateRequestDTO request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // If no authentication is set, the user is not authenticated
        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Authentication required. Please provide a valid JWT token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        String username = authentication.getName();

        try {
            UserDTO updatedProfile = authService.updateUserProfile(username, request);
            return ResponseEntity.ok(updatedProfile);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            if ("Username is already taken".equals(message) || "Email is already registered".equals(message)) {
                Map<String, String> error = new HashMap<>();
                error.put("error", message);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", message);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }
        }
    }

    /**
     * Generate a new JWT token for the currently authenticated user.
     * Useful for API integrations, MCP servers, or when a fresh token is needed.
     * Requires a valid JWT token (authenticated user).
     * Accepts an optional TokenGenerationRequestDTO with expiresAt to set custom expiration.
     * If no expiresAt is provided, the refresh token will never expire (infinite lifetime).
     */
    @PostMapping("/token")
    @Operation(summary = "Generate new access token", description = "Generates a new access and refresh token for the currently authenticated user. Optional: pass expiresAt in request body to set custom expiration; omit for infinite-expiry tokens.")
    @ApiResponse(responseCode = "200", description = "New tokens generated successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    public ResponseEntity<?> generateNewToken(@RequestBody(required = false) TokenGenerationRequestDTO request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // If no authentication is set, the user is not authenticated
        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Authentication required. Please provide a valid JWT token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        // WIKI4AI-197 (REV-2 P0 #6): a caller that arrived with a hashed API token must
        // not be able to mint a JWT. The minted token is self-contained — no row behind
        // it — so revoking the API token would leave the freshly minted credential
        // working. A revocable credential was being used to create an irrevocable one.
        rejectApiTokenMinting(authentication, "mint a JWT session token");

        String username = authentication.getName();
        AuthResponseDTO response = authService.generateNewTokenForUser(username, request);

        if (response == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "User not found in database");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 403 when the request authenticated with a hashed {@code w4a_} token rather than a
     * JWT session (WIKI4AI-197, REV-2 P0 #6).
     * <p>
     * The two kinds of credential are not interchangeable for the purpose of minting. A
     * JWT is a session: it exists because a human signed in, and the endpoints below are
     * how that session hands out long-lived credentials. A {@code w4a_} token is already
     * such a credential — a row in {@code api_tokens} with scopes, an expiry and a revoke
     * button. Letting it mint more means the thing that is supposed to be revocable
     * decides what survives its own revocation: an agent holding a read/write token could
     * hand itself an unbounded supply of credentials, and the operator pressing "revoke"
     * would see the agent keep working.
     * <p>
     * 403 rather than 401 because the caller <i>is</i> authenticated — the credential is
     * valid, it is simply not the kind that may do this. The MCP server mints nothing (it
     * is configured with one token and calls no token endpoint), so this costs no
     * existing integration.
     *
     * @param what the operation, phrased for the message the operator will read
     * @throws AccessDeniedException always, when the caller holds an API token
     */
    private void rejectApiTokenMinting(Authentication authentication, String what) {
        JwtAuthenticationFilter.ApiTokenAuthenticationDetails.from(authentication)
                .ifPresent(details -> {
                    throw new AccessDeniedException("An API token cannot " + what
                            + ": it would create a credential that outlives the token row it was made from, "
                            + "which is the row that makes the caller revocable. Use the API token itself, or "
                            + "sign in to obtain a session token.");
                });
    }

    /**
     * DTO for creating a named API token.
     */
    public record CreateTokenRequest(String name, LocalDateTime expiresAt) {}

    /**
     * Generate a new named API token (JWT access token) for the currently authenticated user.
     * The token is stored with a custom name and optional expiration date.
     * If no expiration date is provided, the token never expires (infinite lifetime).
     */
    @PostMapping("/token/named")
    @Operation(summary = "Generate named API token", description = "Generates a new JWT access token for the authenticated user with a custom name and optional expiration. Stored in database for management.")
    @ApiResponse(responseCode = "201", description = "Named token generated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request (e.g., missing name)")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    public ResponseEntity<?> generateNamedToken(@Valid @RequestBody CreateTokenRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Authentication required. Please provide a valid JWT token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        if (request.name == null || request.name.isBlank()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Token name is required");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }

        // WIKI4AI-197 (REV-2 P0 #6): same rule, worse shape — with no expiresAt the
        // named token carries no exp at all, so one API token could permanently clone
        // itself. The frontend moved to POST /api/v1/tokens (ProfilePage.tsx), so this
        // only costs an agent that was minting tokens for itself.
        rejectApiTokenMinting(authentication, "mint a named API token");

        String username = authentication.getName();
        TokenResponseDTO response = authService.generateNamedApiToken(username, request.name, request.expiresAt);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all API tokens for the currently authenticated user.
     */
    @GetMapping("/tokens")
    @Operation(summary = "List API tokens", description = "Returns all named API tokens for the authenticated user (without token values)")
    @ApiResponse(responseCode = "200", description = "Token list returned successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required - no valid JWT token provided")
    public ResponseEntity<?> listApiTokens() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Authentication required. Please provide a valid JWT token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        UserDTO profile = authService.getProfileByUsername(authentication.getName());
        if (profile == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "User not found in database");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        List<TokenResponseDTO> tokens = authService.listApiTokens(profile.getId());
        return ResponseEntity.ok(tokens);
    }

    /**
     * Delete an API token by ID (only if it belongs to the authenticated user).
     */
    @DeleteMapping("/token/{tokenId}")
    @Operation(summary = "Delete API token", description = "Deletes a named API token. Only the owner can delete their own tokens.")
    @ApiResponse(responseCode = "204", description = "Token deleted successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required or unauthorized")
    @ApiResponse(responseCode = "404", description = "Token not found")
    public ResponseEntity<?> deleteApiToken(@PathVariable Long tokenId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Authentication required. Please provide a valid JWT token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        UserDTO profile = authService.getProfileByUsername(authentication.getName());
        if (profile == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "User not found in database");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        boolean deleted = authService.deleteApiToken(tokenId, profile.getId());
        if (!deleted) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Token not found or unauthorized");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Health check for auth endpoint.
     */
    @GetMapping("/health")
    @Operation(summary = "Auth service health check", description = "Returns the current status of the authentication service")
    public ResponseEntity<Map<String, String>> authHealth() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "auth");
        return ResponseEntity.ok(response);
    }
}
