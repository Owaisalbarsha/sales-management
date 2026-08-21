package com.salesmanagement.shared.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration for the entire application.
 *
 * Key decisions:
 *
 * STATELESS sessions:
 *   We use JWT — the server holds no session state. SessionCreationPolicy.STATELESS
 *   tells Spring Security never to create or use an HttpSession. Every request
 *   is authenticated independently via the JWT in the Authorization header.
 *
 * CSRF disabled:
 *   CSRF attacks require cookies. Our API uses Authorization header Bearer tokens —
 *   no cookies, so CSRF is not a threat vector. Disabling it removes the
 *   requirement for XSRF tokens on every mutating request, which would break
 *   the mobile Flutter client.
 *
 * @EnableMethodSecurity(prePostEnabled = true):
 *   Enables @PreAuthorize("hasRole('...')") on controller methods (R-7).
 *   This is where role enforcement happens — not in the filter, not in services.
 *   proxyTargetClass = true ensures @PreAuthorize works on concrete classes too.
 *
 * Public endpoints (no JWT required):
 *   POST /api/auth/login    — login, issues the token
 *   POST /api/auth/refresh  — token refresh (carries refresh token in body, not access token)
 *   GET  /actuator/health   — health check for load balancers / Docker health checks
 *
 * SSE endpoint:
 *   GET /api/tracking/live is protected — requires SALES_MANAGER or ADMIN.
 *   The @PreAuthorize on TrackingController handles this; listed here for documentation.
 *
 * BCrypt cost factor:
 *   Default strength 10 — ~100ms on a modern CPU. Sufficient for a backend that
 *   only hashes on login and registration. Increase to 12 if dedicated auth service.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // ── Stateless JWT — no sessions, no cookies ──────────────────────
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── CSRF not needed for token-based API ──────────────────────────
                .csrf(AbstractHttpConfigurer::disable)

                // ── CORS handled at the application level (if needed, configure   ─
                //    CorsConfigurationSource bean; not configured here to keep      ─
                //    deployment-specific config out of shared) ─────────────────────
                .cors(AbstractHttpConfigurer::disable)

                // ── Public vs protected endpoints ────────────────────────────────
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints — no token needed
                        .requestMatchers(HttpMethod.POST, "/api/auth/login")  .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()

                        // Health check for Docker / load balancers
                        .requestMatchers("/actuator/health").permitAll()

                        // Spring Modulith actuator endpoints — lock down to ADMIN only
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()

                        // Everything else requires a valid JWT
                        .anyRequest().authenticated()
                )

                // ── Add our JWT filter before the default username/password filter ─
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt password encoder used by identity/internal/UserService for:
     * - Hashing password on user creation / password change (BR-7)
     * - Verifying password on login
     *
     * Declared here (in shared config) so that identity/internal can inject it
     * without defining it themselves — avoids duplicate bean definition errors.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}