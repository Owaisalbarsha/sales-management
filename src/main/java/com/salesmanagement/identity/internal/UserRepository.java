package com.salesmanagement.identity.internal;

import com.salesmanagement.shared.security.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for {@link User} entities.
 *
 * <p>Extends {@link JpaRepository} which provides the standard CRUD operations
 * ({@code findById}, {@code save}, {@code delete}, etc.) out of the box.
 * Only query methods that are not already covered by {@code JpaRepository}
 * are declared here — no redundant method declarations.
 *
 * <p><b>Visibility:</b> package-private by design. Nothing outside
 * {@code identity/internal} may reference this interface, not even other
 * classes within the identity module's {@code api} package. All data access
 * is funnelled through {@link UserService}, which is the only class that
 * holds a reference to this repository.
 *
 * <p><b>Naming convention:</b> Spring Data derives the query from the method
 * name at startup. If a method name is ever misspelled or references a field
 * that does not exist on {@link User}, the application fails to start — not
 * at runtime when the query is first called. This is intentional and desirable:
 * broken queries are caught at boot time, not in production.
 *
 * <p><b>No {@code @Repository} annotation needed:</b> Spring Data detects
 * interfaces extending {@link JpaRepository} automatically during component
 * scanning. Adding the annotation would be redundant.
 */
interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks up a user by their email address.
     *
     * <p>Email is the system's unique login identifier. The value stored in
     * the database is always lowercase — callers must lowercase the input
     * before invoking this method. {@link UserService} enforces this contract.
     *
     * <p>Returns an {@link Optional} rather than {@code null} or throwing an
     * exception — the repository layer does not decide what a missing record means.
     * That decision (throw {@link com.salesmanagement.shared.exception.BusinessException},
     * return a default, etc.) belongs to the service layer.
     *
     * @param email the lowercase email address to search for
     * @return an {@link Optional} containing the user if found, empty otherwise
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks whether any user account is already registered with the given email.
     *
     * <p>Used exclusively in {@link UserService#create} to reject duplicate
     * registrations before the expensive BCrypt hash computation runs.
     * Prefer this over {@code findByEmail(...).isPresent()} — it issues a
     * {@code SELECT 1} existence check instead of loading the full entity.
     *
     * @param email the lowercase email address to check
     * @return {@code true} if an account with this email exists, {@code false} otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Returns all users assigned to the given role.
     *
     * <p>Used for administrative listings (e.g. display all SALES_REP accounts)
     * and by internal services that need to enumerate users of a specific type.
     * Results are returned in insertion order (by {@code id} ascending) as
     * defined by the default PostgreSQL heap order — no explicit sort is needed
     * for the current use cases.
     *
     * @param role the role to filter by; must not be {@code null}
     * @return all users with the specified role; empty list if none found
     */
    List<User> findByRole(UserRole role);

    /**
     * Returns all users with the given account status.
     *
     * <p>Useful for administrative operations such as listing all suspended
     * accounts for review, or identifying inactive accounts for cleanup.
     *
     * @param status the lifecycle status to filter by; must not be {@code null}
     * @return all users in the specified status; empty list if none found
     */
    List<User> findByStatus(UserStatus status);
}