package com.wiki4ai.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for JWT-based authentication.
 * When security.enabled=false (test profile), all endpoints are permitted without auth.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired(required = false)
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final CorsProperties corsProperties;
    private final boolean securityEnabled;

    public SecurityConfig(CorsProperties corsProperties,
                          @Value("${security.enabled:true}") boolean securityEnabled) {
        this.corsProperties = corsProperties;
        this.securityEnabled = securityEnabled;
    }

    /**
     * Configure the security filter chain.
     * - Disables CSRF (stateless JWT auth doesn't need it)
     * - Configures CORS using CorsProperties
     * - Permits /api/v1/auth/** without authentication
     * - When security.enabled=true: requires valid JWT token for all other endpoints
     * - When security.enabled=false: permits all requests (test mode)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(request -> {
                org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
                config.setAllowedOrigins(corsProperties.getAllowedOrigins());
                config.setAllowedMethods(corsProperties.getAllowedMethods());
                config.setAllowedHeaders(corsProperties.getAllowedHeaders());
                config.setAllowCredentials(corsProperties.isAllowCredentials());
                config.setMaxAge(corsProperties.getMaxAge());
                return config;
            }))
            .authorizeHttpRequests(auth -> {
                // Permit all authentication endpoints without authentication
                auth.requestMatchers("/api/v1/auth/**").permitAll();
                // Permit health check and actuator endpoints without authentication
                //
                // /h2-console/** is gone (WIKI4AI-197, REV-2 P0 #9). It was permitAll, so
                // the H2 web console sat outside JWT auth in the deployed stack: a login
                // form that connects to any JDBC URL a visitor types. The console is now
                // also disabled in the postgres profile, and these two are deliberately
                // both changed — permitAll alone would still leave the servlet reachable
                // to anything that can reach the port, and disabling it alone would leave
                // a rule advertising a door that is no longer there.
                auth.requestMatchers("/api/health", "/actuator/**").permitAll();
                // Permit OPTIONS preflight requests for CORS
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                // Permit public read-only project endpoints (list and get by slug) without authentication
                auth.requestMatchers(HttpMethod.GET, "/api/v1/projects").permitAll();
                auth.requestMatchers(HttpMethod.GET, "/api/v1/projects/**").permitAll();
                // When security is enabled: require a valid credential for ALL other
                // endpoints, and for a hashed API token also require the scope that
                // matches the method (WIKI4AI-197, REV-2 P0 #5).
                //
                // The manager subsumes authenticated(): it refuses a null,
                // unauthenticated or anonymous caller itself before it looks at any
                // scope. Keeping both rules as separate matchers would have been the
                // more obvious spelling and the more fragile one — request matchers are
                // evaluated in order and the first match wins, so a later
                // anyRequest().authenticated() would simply never be consulted.
                //
                // When security is disabled (test mode): permit all requests
                if (securityEnabled) {
                    auth.anyRequest().access(new ApiTokenScopeAuthorizationManager());
                } else {
                    auth.anyRequest().permitAll();
                }
            })
            // For unauthenticated access to protected endpoints, return 401 instead of 403
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(
                            (request, response, exception) -> {
                                response.setStatus(org.springframework.http.HttpStatus.UNAUTHORIZED.value());
                                response.setContentType("application/json");
                                response.getWriter().write("{\"message\":\"Authentication required\"}");
                            })
                    // A denial decided in the filter chain — today that means a scope
                    // refusal by ApiTokenScopeAuthorizationManager — never reaches a
                    // controller, so GlobalExceptionHandler cannot shape its body and
                    // Spring's default handler would answer 403 with nothing in it at all.
                    // An agent holding a bare 403 cannot tell a missing scope from a
                    // missing project permission grant, and the two have different
                    // remedies: be issued a token with another scope, versus ask to be
                    // added to the project. So the reason the manager computed on the
                    // request is put in the body, in the shape the rest of the API uses.
                    .accessDeniedHandler((request, response, exception) -> {
                        Object reason = request.getAttribute(
                                ApiTokenScopeAuthorizationManager.DENIAL_REASON_ATTRIBUTE);
                        String message = reason != null ? reason.toString() : "Access denied";
                        response.setStatus(org.springframework.http.HttpStatus.FORBIDDEN.value());
                        response.setContentType("application/json");
                        response.getWriter().write(
                                "{\"error\":\"Forbidden\",\"message\":\""
                                        + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                    }));

        // Only add JWT filter when security is enabled and filter is available
        if (securityEnabled && jwtAuthenticationFilter != null) {
            http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
