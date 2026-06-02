package com.salesmanagement.identity.internal;

import com.salesmanagement.identity.api.UserCreatedEvent;
import com.salesmanagement.identity.internal.dto.CreateUserRequest;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Core business logic for user account management within the {@code identity} module.
 *
 * <p>This service is the single source of truth for all operations that read or
 * mutate {@link User} state. No other class in this module — and certainly no class
 * outside it — bypasses this service to call {@link UserRepository} directly.
 *
 * <p><b>Spring Security integration:</b> implements {@link UserDetailsService} so
 * Spring's {@code AuthenticationManager} can delegate credential verification here
 * during the login flow. The method {@link #loadUserByUsername(String)} is the
 * bridge between Spring Security and our domain model.
 *
 * <p><b>Transaction strategy:</b>
 * <ul>
 *   <li>The class-level {@code @Transactional} is intentionally absent — each method
 *       declares its own boundary explicitly, making the transactional behaviour
 *       visible at a glance rather than inherited invisibly from a class annotation.</li>
 *   <li>All read-only operations use {@code @Transactional(readOnly = true)}, which
 *       signals Hibernate to skip dirty checking and allows the JDBC driver / connection
 *       pool to route the query to a read replica if one is configured in the future.</li>
 *   <li>Write operations use {@code @Transactional} (read-write, default isolation).</li>
 * </ul>
 *
 * <p><b>Event publishing:</b> domain events are published inside the write transaction
 * via {@link ApplicationEventPublisher}. Spring Modulith's Event Publication Log
 * records the event before the transaction commits, guaranteeing at-least-once
 * delivery to listeners even if the server restarts mid-flight (R-4).
 *
 * <p>This class is package-private. The public contract of the identity module is
 * exposed exclusively through {@link com.salesmanagement.identity.api.UserFacade}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository           userRepository;
    private final PasswordEncoder          passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    // ─── Spring Security bridge ───────────────────────────────────────────────

    /**
     * Loads a {@link UserDetails} object for Spring Security's authentication pipeline.
     *
     * <p>Called automatically by {@code AuthenticationManager} during
     * {@code POST /api/auth/login}. The returned object is used solely for
     * password verification — it is not stored or reused after authentication.
     *
     * <p>Accounts with status {@link UserStatus#INACTIVE} or
     * {@link UserStatus#SUSPENDED} are treated as non-existent from the
     * caller's perspective. We intentionally throw {@link UsernameNotFoundException}
     * for all negative cases — not a status-specific exception — to prevent
     * callers from distinguishing "wrong password" from "account suspended",
     * which would leak internal account state.
     *
     * @param email the login identifier; treated as the Spring Security "username"
     * @return a {@link UserDetails} instance ready for password comparison
     * @throws UsernameNotFoundException if no active account exists for the given email
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email.toLowerCase())
                .filter(User::canLogin)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.getEmail())
                        .password(user.getPasswordHash())
                        .roles(user.getRole().name())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No active account found for: " + email));
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /**
     * Retrieves a user by their database identifier.
     *
     * <p>This is the primary lookup method used by
     * {@link com.salesmanagement.identity.api.UserFacade} to serve cross-module
     * queries. All other modules that need user data call the facade, which
     * calls this method.
     *
     * @param userId the primary key of the user to fetch
     * @return the matching {@link User} entity
     * @throws BusinessException if no user exists with the given ID
     */
    @Transactional(readOnly = true)
    public User getById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound(
                        "User not found with id: " + userId,
                        "USER_NOT_FOUND"));
    }

    /**
     * Retrieves a user by their email address.
     *
     * <p>Used internally by {@link AuthController} after successful authentication
     * to load the full {@link User} entity for JWT generation. Email lookup is
     * always case-insensitive — the value is lowercased before querying.
     *
     * @param email the email address to search for
     * @return the matching {@link User} entity
     * @throws BusinessException if no user exists with the given email
     */
    @Transactional(readOnly = true)
    User getByEmail(String email) {
        return userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> BusinessException.notFound(
                        "User not found with email: " + email,
                        "USER_NOT_FOUND"));
    }

    /**
     * Returns all users in the system regardless of role or status.
     *
     * <p>Intended for the ADMIN user list screen. The controller applies
     * {@code @PreAuthorize("hasRole('ADMIN')")} — this method does not
     * re-check authorization, following the single responsibility principle.
     *
     * @return all persisted users, in insertion order
     */
    @Transactional(readOnly = true)
    List<User> getAll() {
        return userRepository.findAll();
    }

    /**
     * Returns all users assigned to a specific role.
     *
     * <p>Used by other services within the identity module that need to
     * enumerate, for example, all SALES_REP accounts.
     *
     * @param role the role to filter by
     * @return users matching the given role; empty list if none exist
     */
    @Transactional(readOnly = true)
    List<User> getByRole(UserRole role) {
        return userRepository.findByRole(role);
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    /**
     * Creates a new user account and publishes a {@link UserCreatedEvent}.
     *
     * <p>The full creation sequence within the transaction:
     * <ol>
     *   <li>Reject duplicate emails before BCrypt runs — hashing is expensive
     *       by design and should not waste CPU on a request that will fail anyway.</li>
     *   <li>Lowercase the email for consistent storage and lookup.</li>
     *   <li>Hash the raw password with BCrypt via {@link PasswordEncoder}.</li>
     *   <li>Persist the entity — new accounts default to {@link UserStatus#ACTIVE}.</li>
     *   <li>Publish {@link UserCreatedEvent} inside the transaction so the Event
     *       Publication Log records it atomically with the INSERT.</li>
     * </ol>
     *
     * <p>Role cannot be changed after creation in the current version.
     * If a user changes role (e.g. rep promoted to manager), the ADMIN
     * deactivates the old account and creates a new one. This keeps the
     * historical records (visits, invoices) tied to the original role identity.
     *
     * @param request validated inbound DTO from {@link com.salesmanagement.identity.internal.UserController}
     * @return the newly created and persisted {@link User} entity
     * @throws BusinessException if the email address is already registered
     */
    @Transactional
    User create(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw BusinessException.conflict(
                    "Email already in use: " + request.email(),
                    "EMAIL_ALREADY_IN_USE");
        }

        User user = new User(
                request.name(),
                request.email().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.role()
        );

        User saved = userRepository.save(user);
        log.info("User created: id={}, role={}", saved.getId(), saved.getRole());

        eventPublisher.publishEvent(
                new UserCreatedEvent(
                        saved.getId(),
                        saved.getName(),
                        saved.getEmail(),
                        saved.getRole()));

        return saved;
    }

    /**
     * Updates the lifecycle status of an existing user account.
     *
     * <p>Status transitions are delegated to the domain methods on {@link User}
     * ({@link User#activate()}, {@link User#deactivate()}, {@link User#suspend()})
     * rather than calling {@code user.setStatus()} directly. This keeps the
     * transition logic — and any future validation around it — inside the entity
     * where it belongs.
     *
     * <p>Hibernate's dirty-checking mechanism detects the field change and issues
     * an {@code UPDATE} on transaction commit. No explicit {@code save()} call
     * is needed.
     *
     * @param userId    the user whose status should be changed
     * @param newStatus the target status to transition to
     * @throws BusinessException if no user exists with the given ID
     */
    @Transactional
    void updateStatus(Long userId, UserStatus newStatus) {
        User user = getById(userId);

        switch (newStatus) {
            case ACTIVE    -> user.activate();
            case INACTIVE  -> user.deactivate();
            case SUSPENDED -> user.suspend();
        }

        log.info("User status updated: id={}, newStatus={}", userId, newStatus);
    }
}