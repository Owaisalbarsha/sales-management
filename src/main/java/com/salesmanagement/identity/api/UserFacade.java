package com.salesmanagement.identity.api;

import com.salesmanagement.identity.internal.UserService;
import com.salesmanagement.identity.internal.User;
import com.salesmanagement.shared.security.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}