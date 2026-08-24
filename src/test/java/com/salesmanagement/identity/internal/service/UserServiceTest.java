package com.salesmanagement.identity.internal.service;

import com.salesmanagement.identity.api.UserCreatedEvent;
import com.salesmanagement.identity.internal.dto.CreateUserRequest;
import com.salesmanagement.identity.internal.dto.UserListResponse;
import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.entity.UserStatus;
import com.salesmanagement.identity.internal.repository.UserRepository;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserPrincipal;
import com.salesmanagement.shared.security.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserService.
 *
 * <p>
 * These tests focus on business logic inside the service layer, mirroring the
 * style used in TerritoryServiceTest. Bean validation (e.g. @NotBlank on
 * CreateUserRequest) is intentionally not exercised here — that is enforced by
 * @Valid at the controller boundary.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private UserService userService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId) {
        UserPrincipal principal = new UserPrincipal(userId, "0000000", UserRole.ADMIN, "ACTIVE");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }


    // ============================================================
    // LOAD USER BY USERNAME (Spring Security bridge)
    // ============================================================

    @Nested
    @DisplayName("Load User By Username Tests")
    class LoadUserByUsernameTests {

        @Test
        @DisplayName("Load active user by phone number succeeds")
        void loadUserByUsername_ActiveUser_ReturnsUserDetails() {

            // Arrange
            User user = new User("Jane", "555000111", "hashed", UserRole.SALES_REP);

            when(userRepository.findByPhoneNumber("555000111"))
                    .thenReturn(Optional.of(user));

            // Act
            UserDetails details = userService.loadUserByUsername("555000111");

            // Assert
            assertThat(details.getUsername()).isEqualTo("555000111");
            assertThat(details.getPassword()).isEqualTo("hashed");
            assertThat(details.getAuthorities())
                    .extracting(Object::toString)
                    .containsExactly("ROLE_SALES_REP");

            verify(userRepository).findByPhoneNumber("555000111");
        }

        @Test
        @DisplayName("Load inactive user is treated as not found")
        void loadUserByUsername_InactiveUser_ThrowsUsernameNotFound() {

            // Arrange
            User user = new User("Jane", "555000111", "hashed", UserRole.SALES_REP);
            user.deactivate();

            when(userRepository.findByPhoneNumber("555000111"))
                    .thenReturn(Optional.of(user));

            // Act & Assert
            assertThatThrownBy(() -> userService.loadUserByUsername("555000111"))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessageContaining("555000111");
        }

        @Test
        @DisplayName("Load unknown phone number throws UsernameNotFoundException")
        void loadUserByUsername_UnknownPhoneNumber_ThrowsUsernameNotFound() {

            // Arrange
            when(userRepository.findByPhoneNumber("000000000"))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> userService.loadUserByUsername("000000000"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }
    }


    // ============================================================
    // GET TESTS
    // ============================================================

    @Nested
    @DisplayName("Get Tests")
    class GetTests {

        @Test
        @DisplayName("Get existing user by id")
        void getById_ExistingUser_ReturnsUser() {

            // Arrange
            User user = new User("Jane", "555000111", "hashed", UserRole.SALES_REP);

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // Act
            User result = userService.getById(1L);

            // Assert
            assertThat(result).isSameAs(user);
            verify(userRepository).findById(1L);
        }

        @Test
        @DisplayName("Get non-existing user by id throws NOT_FOUND")
        void getById_NotFound_ThrowsNotFound() {

            // Arrange
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> userService.getById(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("User not found with id: 999")
                    .extracting("errorCode")
                    .isEqualTo("USER_NOT_FOUND");
        }

        @Test
        @DisplayName("Get existing user by phone number")
        void getByPhoneNumber_ExistingUser_ReturnsUser() {

            // Arrange
            User user = new User("Jane", "555000111", "hashed", UserRole.SALES_REP);

            when(userRepository.findByPhoneNumber("555000111"))
                    .thenReturn(Optional.of(user));

            // Act
            User result = userService.getByPhoneNumber("555000111");

            // Assert
            assertThat(result).isSameAs(user);
        }

        @Test
        @DisplayName("Get user by phone number is case-insensitive (lowercased before lookup)")
        void getByPhoneNumber_MixedCaseInput_LowercasesBeforeLookup() {

            // Arrange
            User user = new User("Jane", "abc555", "hashed", UserRole.SALES_REP);

            when(userRepository.findByPhoneNumber("abc555"))
                    .thenReturn(Optional.of(user));

            // Act
            User result = userService.getByPhoneNumber("ABC555");

            // Assert
            assertThat(result).isSameAs(user);
            verify(userRepository).findByPhoneNumber("abc555");
        }

        @Test
        @DisplayName("Get non-existing user by phone number throws NOT_FOUND")
        void getByPhoneNumber_NotFound_ThrowsNotFound() {

            // Arrange
            when(userRepository.findByPhoneNumber("000000000"))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> userService.getByPhoneNumber("000000000"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("USER_NOT_FOUND");
        }

        @Test
        @DisplayName("Get all users returns repository contents")
        void getAll_ReturnsAllUsers() {

            // Arrange
            List<User> users = List.of(
                    new User("Jane", "1", "h", UserRole.SALES_REP),
                    new User("John", "2", "h", UserRole.ADMIN));

            when(userRepository.findAll()).thenReturn(users);

            // Act & Assert
            assertThat(userService.getAll()).containsExactlyElementsOf(users);
        }

        @Test
        @DisplayName("Get by role delegates to repository")
        void getByRole_DelegatesToRepository() {

            // Arrange
            List<User> reps = List.of(new User("Jane", "1", "h", UserRole.SALES_REP));

            when(userRepository.findByRole(UserRole.SALES_REP)).thenReturn(reps);

            // Act & Assert
            assertThat(userService.getByRole(UserRole.SALES_REP)).containsExactlyElementsOf(reps);
        }
    }


    // ============================================================
    // CREATE TESTS
    // ============================================================

    @Nested
    @DisplayName("Create User Tests")
    class CreateTests {

        @Test
        @DisplayName("Create user with unique phone number succeeds")
        void create_UniquePhoneNumber_ReturnsCreatedUser() {

            // Arrange
            CreateUserRequest request = new CreateUserRequest(
                    "Jane", "555000111", "rawPassword", UserRole.SALES_REP);

            when(userRepository.existsByPhoneNumber("555000111")).thenReturn(false);
            when(passwordEncoder.encode("rawPassword")).thenReturn("hashed");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            User result = userService.create(request);

            // Assert
            assertThat(result.getName()).isEqualTo("Jane");
            assertThat(result.getPhoneNumber()).isEqualTo("555000111");
            assertThat(result.getPasswordHash()).isEqualTo("hashed");
            assertThat(result.getRole()).isEqualTo(UserRole.SALES_REP);
            assertThat(result.getStatus()).isEqualTo(UserStatus.ACTIVE);

            verify(eventPublisher).publishEvent(any(UserCreatedEvent.class));
        }

        @Test
        @DisplayName("Create user lowercases the phone number before checking and storing it")
        void create_MixedCasePhoneNumber_IsLowercased() {

            // Arrange
            CreateUserRequest request = new CreateUserRequest(
                    "Jane", "ABC555", "rawPassword", UserRole.SALES_REP);

            when(userRepository.existsByPhoneNumber("abc555")).thenReturn(false);
            when(passwordEncoder.encode("rawPassword")).thenReturn("hashed");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            User result = userService.create(request);

            // Assert
            assertThat(result.getPhoneNumber()).isEqualTo("abc555");
            verify(userRepository).existsByPhoneNumber("abc555");
        }

        @Test
        @DisplayName("Create user with duplicate phone number throws conflict")
        void create_DuplicatePhoneNumber_ThrowsConflict() {

            // Arrange
            CreateUserRequest request = new CreateUserRequest(
                    "Jane", "555000111", "rawPassword", UserRole.SALES_REP);

            when(userRepository.existsByPhoneNumber("555000111")).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> userService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already in use")
                    .extracting("errorCode")
                    .isEqualTo("PHONE_NUMBER_ALREADY_IN_USE");

            // Duplicate must not reach BCrypt or the repository save.
            verify(passwordEncoder, never()).encode(anyString());
            verify(userRepository, never()).save(any(User.class));
            verify(eventPublisher, never()).publishEvent(any());
        }
    }


    // ============================================================
    // UPDATE STATUS TESTS
    // ============================================================

    @Nested
    @DisplayName("Update Status Tests")
    class UpdateStatusTests {

        @Test
        @DisplayName("Admin cannot change their own account status")
        void updateStatus_SelfModification_ThrowsBadRequest() {

            // Arrange
            authenticateAs(1L);

            // Act & Assert
            assertThatThrownBy(() -> userService.updateStatus(1L, UserStatus.SUSPENDED))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot change your own account status")
                    .extracting("errorCode")
                    .isEqualTo("CANNOT_MODIFY_SELF");

            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("Suspending another user's account invalidates their session")
        void updateStatus_SuspendOtherUser_InvalidatesSession() {

            // Arrange
            authenticateAs(1L);

            User user = new User("Jane", "555000111", "h", UserRole.SALES_REP);
            when(userRepository.findById(2L)).thenReturn(Optional.of(user));

            // Act
            userService.updateStatus(2L, UserStatus.SUSPENDED);

            // Assert
            assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            verify(sessionService).invalidateSession(2L);
        }

        @Test
        @DisplayName("Reactivating a user does not invalidate any session")
        void updateStatus_Reactivate_DoesNotInvalidateSession() {

            // Arrange
            authenticateAs(1L);

            User user = new User("Jane", "555000111", "h", UserRole.SALES_REP);
            user.suspend();
            when(userRepository.findById(2L)).thenReturn(Optional.of(user));

            // Act
            userService.updateStatus(2L, UserStatus.ACTIVE);

            // Assert
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            verify(sessionService, never()).invalidateSession(anyLong());
        }

        @Test
        @DisplayName("Update status of non-existing user throws NOT_FOUND")
        void updateStatus_NotFound_ThrowsNotFound() {

            // Arrange
            authenticateAs(1L);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> userService.updateStatus(999L, UserStatus.INACTIVE))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("USER_NOT_FOUND");
        }
    }


    // ============================================================
    // PASSWORD TESTS
    // ============================================================

    @Nested
    @DisplayName("Password Tests")
    class PasswordTests {

        @Test
        @DisplayName("Admin reset password re-hashes and invalidates the session")
        void resetPassword_Success() {

            // Arrange
            User user = new User("Jane", "555000111", "oldHash", UserRole.SALES_REP);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("newRawPassword")).thenReturn("newHash");

            // Act
            userService.resetPassword(1L, "newRawPassword");

            // Assert
            assertThat(user.getPasswordHash()).isEqualTo("newHash");
            verify(sessionService).invalidateSession(1L);
        }

        @Test
        @DisplayName("Change password with correct current password succeeds")
        void changePassword_CorrectCurrentPassword_Success() {

            // Arrange
            User user = new User("Jane", "555000111", "oldHash", UserRole.SALES_REP);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldRaw", "oldHash")).thenReturn(true);
            when(passwordEncoder.encode("newRaw")).thenReturn("newHash");

            // Act
            userService.changePassword(1L, "oldRaw", "newRaw");

            // Assert
            assertThat(user.getPasswordHash()).isEqualTo("newHash");
            verify(sessionService).invalidateSession(1L);
        }

        @Test
        @DisplayName("Change password with wrong current password throws and keeps old hash")
        void changePassword_WrongCurrentPassword_ThrowsBadRequest() {

            // Arrange
            User user = new User("Jane", "555000111", "oldHash", UserRole.SALES_REP);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrongRaw", "oldHash")).thenReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> userService.changePassword(1L, "wrongRaw", "newRaw"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Current password is incorrect")
                    .extracting("errorCode")
                    .isEqualTo("WRONG_PASSWORD");

            assertThat(user.getPasswordHash()).isEqualTo("oldHash");
            verify(sessionService, never()).invalidateSession(anyLong());
        }
    }


    // ============================================================
    // SEARCH USERS TESTS
    // ============================================================

    @Nested
    @DisplayName("Search Users Tests")
    class SearchUsersTests {

        @Test
        @DisplayName("Search combines the paginated page with global status counts")
        void searchUsers_ReturnsPageAndGlobalCounts() {

            // Arrange
            PageRequest pageRequest = new PageRequest();
            Pageable pageable = pageRequest.toPageable();

            User user = new User("Jane", "555000111", "h", UserRole.SALES_REP);
            Page<User> page = new PageImpl<>(List.of(user), pageable, 1);

            when(userRepository.search("term", UserRole.SALES_REP, UserStatus.ACTIVE, pageable))
                    .thenReturn(page);
            when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(5L);
            when(userRepository.countByStatus(UserStatus.INACTIVE)).thenReturn(2L);
            when(userRepository.countByStatus(UserStatus.SUSPENDED)).thenReturn(1L);

            // Act
            UserListResponse response = userService.searchUsers(
                    "term", UserRole.SALES_REP, UserStatus.ACTIVE, pageRequest);

            // Assert
            assertThat(response.users().getContent()).hasSize(1);
            assertThat(response.counts().active()).isEqualTo(5L);
            assertThat(response.counts().inactive()).isEqualTo(2L);
            assertThat(response.counts().suspended()).isEqualTo(1L);
            assertThat(response.counts().total()).isEqualTo(8L);
        }

        @Test
        @DisplayName("A visible-role scope overrides the caller's role filter")
        void searchUsers_VisibleRole_OverridesCallerSuppliedRoleFilter() {

            // Arrange — a SALES_MANAGER asking for ADMIN accounts
            PageRequest pageRequest = new PageRequest();
            Pageable pageable = pageRequest.toPageable();

            Page<User> page = new PageImpl<>(List.of(), pageable, 0);

            when(userRepository.search("", UserRole.SALES_REP, null, pageable))
                    .thenReturn(page);

            // Act
            userService.searchUsers(
                    null, UserRole.ADMIN, null, pageRequest, UserRole.SALES_REP);

            // Assert — the requested ADMIN filter never reaches the repository
            verify(userRepository).search("", UserRole.SALES_REP, null, pageable);
            verify(userRepository, never()).search("", UserRole.ADMIN, null, pageable);
        }

        @Test
        @DisplayName("A visible-role scope also scopes the status counts")
        void searchUsers_VisibleRole_ScopesStatusCounts() {

            // Arrange
            PageRequest pageRequest = new PageRequest();
            Pageable pageable = pageRequest.toPageable();

            Page<User> page = new PageImpl<>(List.of(), pageable, 0);

            when(userRepository.search("", UserRole.SALES_REP, null, pageable))
                    .thenReturn(page);
            when(userRepository.countByStatusAndRole(UserStatus.ACTIVE, UserRole.SALES_REP))
                    .thenReturn(4L);
            when(userRepository.countByStatusAndRole(UserStatus.INACTIVE, UserRole.SALES_REP))
                    .thenReturn(1L);
            when(userRepository.countByStatusAndRole(UserStatus.SUSPENDED, UserRole.SALES_REP))
                    .thenReturn(0L);

            // Act
            UserListResponse response = userService.searchUsers(
                    null, null, null, pageRequest, UserRole.SALES_REP);

            // Assert — counts come from the scoped query, never the global one
            assertThat(response.counts().active()).isEqualTo(4L);
            assertThat(response.counts().inactive()).isEqualTo(1L);
            assertThat(response.counts().suspended()).isEqualTo(0L);
            assertThat(response.counts().total()).isEqualTo(5L);
            verify(userRepository, never()).countByStatus(any());
        }

        @Test
        @DisplayName("A null visible-role leaves the caller's filter and global counts intact")
        void searchUsers_NullVisibleRole_KeepsCallerFilterAndGlobalCounts() {

            // Arrange — an ADMIN is unrestricted
            PageRequest pageRequest = new PageRequest();
            Pageable pageable = pageRequest.toPageable();

            Page<User> page = new PageImpl<>(List.of(), pageable, 0);

            when(userRepository.search("", UserRole.ADMIN, null, pageable)).thenReturn(page);
            when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(5L);
            when(userRepository.countByStatus(UserStatus.INACTIVE)).thenReturn(2L);
            when(userRepository.countByStatus(UserStatus.SUSPENDED)).thenReturn(1L);

            // Act
            UserListResponse response = userService.searchUsers(
                    null, UserRole.ADMIN, null, pageRequest, null);

            // Assert
            verify(userRepository).search("", UserRole.ADMIN, null, pageable);
            assertThat(response.counts().total()).isEqualTo(8L);
            verify(userRepository, never()).countByStatusAndRole(any(), any());
        }

        @Test
        @DisplayName("Blank search term is normalised to no search")
        void searchUsers_BlankSearchTerm_NormalisedToEmptyString() {

            // Arrange
            PageRequest pageRequest = new PageRequest();
            Pageable pageable = pageRequest.toPageable();

            Page<User> page = new PageImpl<>(List.of(), pageable, 0);

            when(userRepository.search("", null, null, pageable)).thenReturn(page);

            // Act
            userService.searchUsers("   ", null, null, pageRequest);

            // Assert
            verify(userRepository).search("", null, null, pageable);
        }
    }


    // ============================================================
    // MISC LOOKUP TESTS
    // ============================================================

    @Nested
    @DisplayName("Misc Lookup Tests")
    class MiscLookupTests {

        @Test
        @DisplayName("Get names by ids returns id-to-name map, skipping ids not found")
        void getNamesByIds_MixOfFoundAndMissing_ReturnsOnlyFound() {

            // Arrange
            User user = new User("Jane", "555000111", "h", UserRole.SALES_REP);
            user.setId(1L);

            when(userRepository.findAllById(Set.of(1L, 2L))).thenReturn(List.of(user));

            // Act
            Map<Long, String> names = userService.getNamesByIds(Set.of(1L, 2L));

            // Assert
            assertThat(names).containsExactly(Map.entry(1L, "Jane"));
        }

        @Test
        @DisplayName("Get names by ids with null ids returns empty map without querying")
        void getNamesByIds_NullIds_ReturnsEmptyMap() {

            // Act
            Map<Long, String> names = userService.getNamesByIds(null);

            // Assert
            assertThat(names).isEmpty();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("Get names by ids with empty set returns empty map without querying")
        void getNamesByIds_EmptyIds_ReturnsEmptyMap() {

            // Act
            Map<Long, String> names = userService.getNamesByIds(Set.of());

            // Assert
            assertThat(names).isEmpty();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("Find active user ids delegates to repository")
        void findActiveUserIds_DelegatesToRepository() {

            // Arrange
            when(userRepository.findIdsByStatus(UserStatus.ACTIVE)).thenReturn(List.of(1L, 2L));

            // Act & Assert
            assertThat(userService.findActiveUserIds()).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("Find active user ids by role delegates to repository")
        void findActiveUserIdsByRole_DelegatesToRepository() {

            // Arrange
            when(userRepository.findIdsByStatusAndRole(UserStatus.ACTIVE, UserRole.WAREHOUSE_MANAGER))
                    .thenReturn(List.of(3L));

            // Act & Assert
            assertThat(userService.findActiveUserIdsByRole(UserRole.WAREHOUSE_MANAGER))
                    .containsExactly(3L);
        }
    }
}