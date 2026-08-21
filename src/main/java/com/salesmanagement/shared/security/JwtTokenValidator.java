package com.salesmanagement.shared.security;

/**
 * Contract for JWT token validation, decoupling the security filter
 * in shared from the identity module's internal JwtService implementation.
 *
 * JwtAuthFilter depends on this interface — never on JwtService directly.
 */
public interface JwtTokenValidator {
    boolean isTokenValid(String token);
    String  extractSubject(String token);
    long    extractUserId(String token);
    java.util.Date extractExpiry(String token);
}