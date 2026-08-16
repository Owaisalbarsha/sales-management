package com.salesmanagement.identity.api;

import com.salesmanagement.identity.internal.service.UserService;
import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.repository.UserRepository;
import com.salesmanagement.shared.security.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
                user.getEmail(), user.getRole());
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
}