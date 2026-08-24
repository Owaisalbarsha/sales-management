package com.salesmanagement.identity.internal.repository;

import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.entity.UserStatus;
import com.salesmanagement.shared.security.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
 * is funnelled through {@code UserService}, which is the only class that
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
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks up a user by their phone number.
     *
     * <p>Phone number is the system's unique login identifier. The value stored in
     * the database is always lowercase — callers must lowercase the input
     * before invoking this method. {@link com.salesmanagement.identity.internal.service.UserService}
     * enforces this contract.
     *
     * <p>Returns an {@link Optional} rather than {@code null} or throwing an
     * exception — the repository layer does not decide what a missing record means.
     * That decision belongs to the service layer.
     *
     * @return an {@link Optional} containing the user if found, empty otherwise
     */
    Optional<User> findByPhoneNumber(String phoneNumber);

    /**
     * Checks whether any user account is already registered with the given phone number.
     *
     * <p>Used exclusively in {@code UserService.create} to reject duplicate
     * registrations before the expensive BCrypt hash computation runs.
     * Prefer this over {@code findByPhoneNumber(...).isPresent()} — it issues a
     * {@code SELECT 1} existence check instead of loading the full entity.
     *
     * @return {@code true} if an account with this phone number exists, {@code false} otherwise
     */
    boolean existsByPhoneNumber(String phoneNumber);

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

    /**
     * Searches and filters users with pagination.
     *
     * <p>All three filter parameters are optional — a {@code null} value means
     * "do not filter by this field". This is the standard JPQL pattern for
     * optional filters: {@code (:param IS NULL OR column = :param)}.
     *
     * <ul>
     *   <li>{@code search} — matches against name OR phone number, case-insensitive,
     *       partial match. {@code null} or blank returns all.</li>
     *   <li>{@code role}   — exact role match. {@code null} returns all roles.</li>
     *   <li>{@code status} — exact status match. {@code null} returns all statuses.</li>
     * </ul>
     *
     * @param search partial name/phone number search term, or {@code null}
     * @param role   role filter, or {@code null} for all
     * @param status status filter, or {@code null} for all
     * @param pageable pagination and sorting
     * @return a page of matching users
     */
    @Query("""
        SELECT u FROM User u
        WHERE (:search = ''
               OR LOWER(u.name)  LIKE LOWER(CONCAT('%', :search, '%'))
               OR u.phoneNumber LIKE CONCAT('%', :search, '%'))
          AND (:role   IS NULL OR u.role   = :role)
          AND (:status IS NULL OR u.status = :status)
        """)
    Page<User> search(@Param("search") String search,
                      @Param("role")   UserRole role,
                      @Param("status") UserStatus status,
                      Pageable pageable);

    /**
     * Counts users with the given status.
     * Called three times (once per status) to build {@code UserStatusCounts}.
     * Three indexed COUNT queries are cheaper and clearer than one GROUP BY
     * with manual result-set mapping.
     *
     * @param status the status to count
     * @return number of users with that status
     */
    long countByStatus(UserStatus status);

    /**
     * Counts users with the given status, restricted to a single role.
     *
     * <p>Backs the summary cards for callers whose view of the user base is
     * scoped to one role — a SALES_MANAGER sees SALES_REP accounts only, so
     * their counts must be scoped the same way the list is. ADMIN callers keep
     * using the unscoped {@link #countByStatus(UserStatus)}.
     *
     * @param status the status to count
     * @param role   the role to restrict the count to
     * @return number of users with that status and role
     */
    long countByStatusAndRole(UserStatus status, UserRole role);

    /** Ids of all users in a given status. (ACTIVE) => announcement audience, FR-108. */
    @Query("select u.id from User u where u.status = :status")
    List<Long> findIdsByStatus(@Param("status") UserStatus status);

    /** Ids of all users in a given status AND role. (ACTIVE + stock roles) => FR-106. */
    @Query("select u.id from User u where u.status = :status and u.role = :role")
    List<Long> findIdsByStatusAndRole(@Param("status") UserStatus status,
                                      @Param("role") UserRole role);

}