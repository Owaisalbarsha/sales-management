package com.salesmanagement.identity.api;

import com.salesmanagement.identity.internal.service.UserService;
import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.repository.UserRepository;
import com.salesmanagement.shared.security.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public API surface of the identity module.
 * The only type other modules may import from identity.
 *
 * Delegates to UserService — business logic never lives here.
 */
@Service
@RequiredArgsConstructor
public class UserFacade {

    private final UserService userService;
    private final UserRepository userRepository;

    public UserInfo findById(Long userId) {
        User user = userService.getById(userId);
        return new UserInfo(user.getId(), user.getName(),
                user.getPhoneNumber(), user.getRole());
    }

    public UserRole getRoleById(Long userId) {
        return userService.getById(userId).getRole();
    }

    public boolean isActive(Long userId) {
        return userService.getById(userId).canLogin();
    }

    public String getNameById(Long userId) {
        return userService.getById(userId).getName();
    }

    /**
     * Batch-resolves user names for a set of ids. Returns a map of id -> name.
     * Ids not found are absent from the map (no exception).
     */
    public Map<Long, String> getNamesByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    /**
     * The ids of every ACTIVE user. Added for the notification module's FR-108
     * announcement fan-out, which must address all active users without reaching
     * into identity's tables. Read-only; ids only (the caller writes one
     * notification row per recipient and needs nothing more).
     *
     * @return active user ids; empty if there are none
     */
    public List<Long> findActiveUserIds() {
        return userService.findActiveUserIds();
    }

    /**
     * The ids of every ACTIVE user holding a given role. Added for the
     * notification module's FR-106 low-stock alerts, which target the
     * stock-managing roles (WAREHOUSE_MANAGER, SALES_MANAGER, ADMIN) without
     * crossing into identity's tables. Read-only.
     *
     * @param role the role to filter by
     * @return active user ids with that role; empty if there are none
     */
    public List<Long> findActiveUserIdsByRole(UserRole role) {
        return userService.findActiveUserIdsByRole(role);
    }
}