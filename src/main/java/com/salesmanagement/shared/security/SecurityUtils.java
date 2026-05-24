package com.salesmanagement.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Static convenience helpers for accessing the authenticated user from anywhere
 * in the application without injecting SecurityContext boilerplate.
 *
 * Usage:
 *   Long   repId = SecurityUtils.getCurrentUserId();
 *   UserPrincipal me = SecurityUtils.getCurrentUser();
 *
 * Why static:
 *   Services deep in the call stack often need the current user's ID
 *   (e.g. InvoiceService setting RepresentativeID). Injecting a request-scoped
 *   bean or passing userId through every method signature is noise. Static access
 *   to SecurityContextHolder is the standard Spring idiom for this.
 *
 * Thread safety:
 *   SecurityContextHolder defaults to MODE_THREADLOCAL — each request thread has
 *   its own SecurityContext. Static access is therefore thread-safe for standard
 *   Tomcat/Undertow web servers. If virtual threads (Project Loom) are enabled
 *   in a future upgrade, set SecurityContextHolder.setStrategyName(
 *   SecurityContextHolder.MODE_INHERITABLETHREADLOCAL) to propagate context
 *   across virtual thread boundaries.
 *
 * Error behaviour:
 *   If called from a context with no authentication (e.g. a scheduled job, a test
 *   that doesn't set up the SecurityContext), throws IllegalStateException with a
 *   clear message rather than a NullPointerException that is hard to trace.
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // utility class — no instances
    }

    /**
     * Returns the full UserPrincipal of the currently authenticated user.
     *
     * @throws IllegalStateException if there is no authenticated user in context
     */
    public static UserPrincipal getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalStateException(
                    "No authenticated user in SecurityContext. " +
                            "Ensure this is called from within an authenticated request thread.");
        }
        return (UserPrincipal) auth.getPrincipal();
    }

    /**
     * Returns only the userId (USER.UserID PK) of the currently authenticated user.
     * The most common access pattern in services — avoids full principal retrieval
     * when only the ID is needed.
     *
     * @throws IllegalStateException if there is no authenticated user in context
     */
    public static Long getCurrentUserId() {
        return getCurrentUser().getUserId();
    }

    /**
     * Returns the role of the currently authenticated user.
     *
     * @throws IllegalStateException if there is no authenticated user in context
     */
    public static UserRole getCurrentUserRole() {
        return getCurrentUser().getRole();
    }

    /**
     * Returns true if there is an authenticated, non-anonymous user in context.
     * Useful in code paths that are optionally authenticated.
     */
    public static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof UserPrincipal;
    }
}