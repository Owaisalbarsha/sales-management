package com.salesmanagement.identity.internal.controller;

import com.salesmanagement.identity.internal.dto.UserListResponse;
import com.salesmanagement.identity.internal.entity.UserStatus;
import com.salesmanagement.identity.internal.dto.CreateUserRequest;
import com.salesmanagement.identity.internal.dto.ResetPasswordRequest;
import com.salesmanagement.identity.internal.dto.UserResponse;
import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.service.UserService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.SecurityUtils;
import com.salesmanagement.shared.security.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for user account management — ADMIN, plus a narrow read-only
 * slice for SALES_MANAGER (BR-7).
 *
 * <p>Exposes CRUD operations for managing system users: listing accounts,
 * retrieving a single account, creating new accounts, and updating account status.
 * Authorization is declared per endpoint via {@code @PreAuthorize} — not enforced
 * in the service layer or UI, but at the API layer where Spring Security
 * intercepts the request (R-7).
 *
 * <p><b>Who may call what:</b>
 * <ul>
 *   <li><b>Every write endpoint</b> ({@code POST}, {@code PATCH}) — {@code ADMIN}
 *       only. User administration is not delegated.</li>
 *   <li><b>The two read endpoints</b> — {@code ADMIN} sees every account;
 *       {@code SALES_MANAGER} sees {@code SALES_REP} accounts and nothing else.
 *       Managers need a rep roster to address demand orders and assign routes;
 *       that need does not extend to enumerating admins, warehouse managers, or
 *       their peers — nor to their phone numbers. The restriction is applied
 *       server-side in {@link #visibleRoleForCurrentUser()} and cannot be widened
 *       by passing a different {@code ?role=} value.</li>
 * </ul>
 *
 * <p><b>What is intentionally missing:</b>
 * <ul>
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
     * Lists users with optional search, filtering, pagination, and status counts.
     *
     * <p>Query parameters (all optional):
     * <ul>
     *   <li>{@code search}  — partial match on name or phone number</li>
     *   <li>{@code role}    — filter by exact role</li>
     *   <li>{@code status}  — filter by exact status</li>
     *   <li>{@code page}, {@code size}, {@code sortBy}, {@code sortDir} — pagination</li>
     * </ul>
     *
     * <p>Example:
     * {@code GET /api/users?search=ahmed&status=ACTIVE&page=0&size=20&sortBy=name&sortDir=asc}
     *
     * <p>The response carries the paginated user list plus status counts for the
     * dashboard summary cards — global for an ADMIN, scoped to {@code SALES_REP}
     * for a SALES_MANAGER so the cards never count accounts the caller cannot list.
     *
     * <p>For a SALES_MANAGER the {@code role} parameter is ignored: their view is
     * pinned to {@code SALES_REP} regardless of what they ask for.
     *
     * @param search      optional name/phone number search term
     * @param role        optional role filter (ADMIN only; ignored for SALES_MANAGER)
     * @param status      optional status filter
     * @param pageRequest pagination parameters (bound from query string)
     * @return {@code 200 OK} with {@link UserListResponse}
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<ApiResponse<UserListResponse>> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            @Valid PageRequest pageRequest) {

        return ResponseEntity.ok(ApiResponse.ok(userService.searchUsers(
                search, role, status, pageRequest, visibleRoleForCurrentUser())));
    }

    /**
     * Returns a single user account by its database identifier.
     *
     * <p>Used by the ADMIN detail screen, by the SALES_MANAGER rep picker, and for
     * lookups during user management operations. Returns the full
     * {@link UserResponse} including status and creation timestamp.
     *
     * <p>A SALES_MANAGER may only read {@code SALES_REP} accounts — otherwise the
     * scoping on {@code GET /api/users} would be pointless, since ids are guessable.
     * The check lives here rather than in {@code UserService.getById} on purpose:
     * that method also backs {@link com.salesmanagement.identity.api.UserFacade}, so
     * guarding it would break every cross-module name lookup made while a manager's
     * own request is on the stack.
     *
     * @param id the database primary key of the user to retrieve
     * @return {@code 200 OK} with the matching user
     * @throws com.salesmanagement.shared.exception.BusinessException
     *         {@code 404} if no user exists with the given ID;
     *         {@code 403} if the caller may not view an account with that role
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable Long id) {
        User user = userService.getById(id);

        UserRole visibleRole = visibleRoleForCurrentUser();
        if (visibleRole != null && user.getRole() != visibleRole) {
            throw BusinessException.forbidden(
                    "You are not allowed to view this user account",
                    "USER_NOT_VISIBLE");
        }

        return ResponseEntity.ok(ApiResponse.ok(UserResponse.from(user)));
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
     *         {@code 409} if the phone number is already registered
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

    @PatchMapping("/{id}/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable Long id,
            @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.ok(ApiResponse.noContent("Password reset successfully"));
    }

    // ─── Visibility rule ────────────────────────────────────────────

    /**
     * The single role the current caller may see through this controller's read
     * endpoints, or {@code null} if they may see every account.
     *
     * <p>{@code ADMIN} is unrestricted. Anything else that clears {@code @PreAuthorize}
     * on a read endpoint — today only {@code SALES_MANAGER} — is confined to
     * {@code SALES_REP}. Defaulting to the narrow answer instead of enumerating the
     * restricted roles means a role added to a {@code hasAnyRole(...)} later cannot
     * silently inherit the full roster.
     */
    private UserRole visibleRoleForCurrentUser() {
        return SecurityUtils.getCurrentUserRole() == UserRole.ADMIN
                ? null
                : UserRole.SALES_REP;
    }
}