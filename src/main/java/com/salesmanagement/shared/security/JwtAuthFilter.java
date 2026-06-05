package com.salesmanagement.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT authentication filter — runs exactly once per request.
 *
 * Responsibility chain:
 * 1. Extract "Bearer <token>" from Authorization header.
 * 2. Parse and validate the JWT (signature, expiry, structure).
 * 3. Extract claims: userId, email, role, status.
 * 4. Build UserPrincipal, wrap in UsernamePasswordAuthenticationToken.
 * 5. Store in SecurityContextHolder for the duration of the request.
 * 6. Let the filter chain continue — @PreAuthorize handles role enforcement.
 *
 * What it does NOT do:
 * - It does NOT hit the database on every request. All auth data is in the JWT.
 *   This is the performance advantage of JWT over session tokens.
 * - It does NOT handle login. That is AuthController in identity/internal.
 * - It does NOT handle token refresh. That is a separate endpoint in identity.
 *
 * Token blacklist (logout / single-session enforcement FR-5):
 *   The identity module maintains a blacklist (Redis SET or DB table, configured
 *   via application.yml profile). This filter calls a TokenBlacklistChecker
 *   interface that is injected. The default implementation is a no-op bean
 *   in shared (always returns false = not blacklisted). The identity module
 *   provides the real implementation via @Primary when it is in context.
 *   This avoids shared depending on Redis or identity internals.
 *
 * JWT claims expected:
 *   sub   : email (String)
 *   userId: Long
 *   role  : UserRole name (String, e.g. "SALES_REP")
 *   status: String ("ACTIVE" | "INACTIVE" | "SUSPENDED")
 *
 * Secret key:
 *   Read from application.yml jwt.secret — must be at least 256 bits (32 chars).
 *   Never hardcode. Rotated via environment variable in production.
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final SecretKey           secretKey;
    private final TokenBlacklistChecker blacklistChecker;

    public JwtAuthFilter(
            @Value("${jwt.secret}") String secret,
            TokenBlacklistChecker blacklistChecker) {

        // HMAC-SHA-256 key derived from the configured secret string.
        // Keys.hmacShaKeyFor validates that the secret is long enough (>=256 bits).
        this.secretKey        = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.blacklistChecker = blacklistChecker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // No Authorization header or not a Bearer token — pass through.
        // SecurityConfig marks public endpoints as permitAll(); Spring Security
        // will reject unauthenticated access to protected endpoints downstream.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7); // strip "Bearer "

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String jti = claims.getId(); // JWT ID for blacklist check

            // Blacklist check: covers logged-out tokens and single-session enforcement.
            if (jti != null && blacklistChecker.isBlacklisted(jti)) {
                log.warn("Blacklisted JWT used: jti={}", jti);
                sendUnauthorized(response, "Token has been invalidated. Please log in again.");
                return;
            }

            Long     userId = claims.get("userId", Long.class);
            String   email  = claims.getSubject();
            String   role   = claims.get("role",   String.class);
            String   status = claims.get("status", String.class);

            if (userId == null || email == null || role == null || status == null) {
                log.warn("JWT missing required claims");
                sendUnauthorized(response, "Invalid token structure");
                return;
            }

            UserRole userRole;
            try {
                userRole = UserRole.valueOf(role);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown role in JWT: {}", role);
                sendUnauthorized(response, "Invalid token: unknown role");
                return;
            }

            UserPrincipal principal = new UserPrincipal(userId, email, userRole, status);

            // isEnabled() returns false for INACTIVE, isAccountNonLocked() for SUSPENDED.
            // Spring Security's pre-auth checks will reject them with 403,
            // but we check here explicitly to send 401 instead with a clear message.
            if (!principal.isEnabled()) {
                sendUnauthorized(response, "Account is inactive");
                return;
            }
            if (!principal.isAccountNonLocked()) {
                sendUnauthorized(response, "Account is suspended");
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,                         // no credentials needed post-auth
                            principal.getAuthorities()
                    );
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            log.debug("Expired JWT for request {}: {}", request.getRequestURI(), e.getMessage());
            sendUnauthorized(response, "Token has expired. Please log in again.");
            return;
        } catch (JwtException e) {
            log.warn("Invalid JWT for request {}: {}", request.getRequestURI(), e.getMessage());
            sendUnauthorized(response, "Invalid token");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Writes a 401 JSON response in the ApiResponse envelope format.
     * We can't use GlobalExceptionHandler here because filters run before
     * the DispatcherServlet — exception handlers are not in scope.
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\",\"data\":null,\"errors\":null}"
        );
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout");
    }
}