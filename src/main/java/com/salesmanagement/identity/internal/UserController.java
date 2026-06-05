package com.salesmanagement.identity.internal;

import com.salesmanagement.identity.internal.dto.CreateUserRequest;
import com.salesmanagement.identity.internal.dto.ResetPasswordRequest;
import com.salesmanagement.identity.internal.dto.UserResponse;
import com.salesmanagement.shared.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for user account management — ADMIN only (BR-7).
 *
 * <p>Exposes CRUD operations for managing system users: listing accounts,
 * retrieving a single account, creating new accounts, and updating account status.
 * Every endpoint in this controller is restricted to {@code ADMIN} role via
 * {@code @PreAuthorize} — not enforced in the service layer or UI, but at the
 * API layer where Spring Security intercepts the request (R-7).
 *
 * <p><b>What is intentionally missing:</b>
 * <ul>
 *   <li><b>Password update</b> — not in the current SRS scope. When added, it will
 *       be a separate endpoint ({@code PATCH /api/users/{id}/password}) requiring
 *       the current password for verification.</li>
 *   <li><b>Role update</b> — roles are fixed at creation time. Changing a user's
 *       role requires deactivating the account and creating a new one, preserving
 *       the integrity of historical records tied to the original role identity.</li>
 *   <li><b>Hard delete</b> — accounts are deactivated, never deleted. Visits,
 *       invoices, and GPS logs reference {@code userId} as a plain ID column;
 *       deleting a user would orphan all historical records.</li>
 * </ul>
 *
 * <p>This class is public so Spring's component scan can proxy it across
 * sub-packages within the identity module. Spring Modulith's {@code verify()}
 * still prevents any class outside the identity module from importing it.
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // ─── Endpoints ────────────────────────────────────────────────────────────

    /**
     * Returns all user accounts in the system regardless of role or status.
     *
     * <p>Intended for the ADMIN user management screen. The response includes
     * status so the UI can distinguish active, inactive, and suspended accounts
     * without a second request per user.
     *
     * @return {@code 200 OK} with a list of all users; empty list if none exist
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAll() {
        List<UserResponse> users = userService.getAll()
                .stream()
                .map(UserResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(users));
    }

    /**
     * Returns a single user account by its database identifier.
     *
     * <p>Used by the ADMIN detail screen and for lookups during user management
     * operations. Returns the full {@link UserResponse} including status and
     * creation timestamp.
     *
     * @param id the database primary key of the user to retrieve
     * @return {@code 200 OK} with the matching user
     * @throws com.salesmanagement.shared.exception.BusinessException
     *         {@code 404} if no user exists with the given ID
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.ok(UserResponse.from(userService.getById(id))));
    }

    /**
     * Creates a new user account.
     *
     * <p>New accounts are always created with {@link UserStatus#ACTIVE} status.
     * The ADMIN supplies the initial password — the user should be instructed
     * to change it on first login (future enhancement, not in current SRS scope).
     *
     * <p>Publishes a {@link com.salesmanagement.identity.api.UserCreatedEvent}
     * inside the creation transaction via {@link UserService#create}, allowing
     * other modules (e.g. notification) to react without coupling to identity.
     *
     * @param request validated account creation payload
     * @return {@code 201 Created} with the newly created user
     * @throws com.salesmanagement.shared.exception.BusinessException
     *         {@code 409} if the email address is already registered
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> create(
            @Valid @RequestBody CreateUserRequest request) {
        UserResponse created = UserResponse.from(userService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(created));
    }

    /**
     * Updates the lifecycle status of an existing user account.
     *
     * <p>Valid transitions:
     * <pre>
     *   ACTIVE    → INACTIVE   (permanent deactivation, e.g. rep has left)
     *   ACTIVE    → SUSPENDED  (temporary restriction, e.g. policy violation)
     *   SUSPENDED → ACTIVE     (restriction lifted)
     *   INACTIVE  → ACTIVE     (re-hire scenario)
     * </pre>
     *
     * <p>An ADMIN cannot change their own status through this endpoint —
     * that would allow self-deactivation, locking the system out of its only
     * admin account. This guard is enforced in {@link UserService#updateStatus}.
     *
     * @param id     the database primary key of the user to update
     * @param status the target lifecycle status
     * @return {@code 200 OK} with no body on success
     * @throws com.salesmanagement.shared.exception.BusinessException
     *         {@code 404} if no user exists with the given ID
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> updateStatus(
            @PathVariable Long id,
            @RequestParam UserStatus status) {
        userService.updateStatus(id, status);
        return ResponseEntity.ok(
                ApiResponse.noContent("User status updated successfully"));
    }

    // UserController.java — add this endpoint

    @PatchMapping("/{id}/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable Long id,
            @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.ok(ApiResponse.noContent("Password reset successfully"));
    }
}