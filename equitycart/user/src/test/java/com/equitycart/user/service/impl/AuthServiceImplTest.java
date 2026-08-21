package com.equitycart.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.exception.AuthenticationException;
import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.user.dto.AuthResponse;
import com.equitycart.user.dto.LoginRequest;
import com.equitycart.user.dto.RefreshRequest;
import com.equitycart.user.dto.RegisterRequest;
import com.equitycart.user.entity.RefreshToken;
import com.equitycart.user.entity.Role;
import com.equitycart.user.entity.User;
import com.equitycart.user.entity.UserRole;
import com.equitycart.user.enums.UserRoles;
import com.equitycart.user.repository.RefreshTokenRepository;
import com.equitycart.user.repository.RoleRepository;
import com.equitycart.user.repository.UserRepository;
import com.equitycart.user.repository.UserRoleRepository;
import com.equitycart.user.repository.WalletAccountRepository;
import com.equitycart.user.service.api.JwtService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

  @Mock private UserRepository userRepository;
  @Mock private UserRoleRepository userRoleRepository;
  @Mock private WalletAccountRepository walletAccountRepository;
  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private RoleRepository roleRepository;
  @Mock private JwtService jwtService;

  @InjectMocks private AuthServiceImpl authService;

  @BeforeEach
  void init() {
    ReflectionTestUtils.setField(authService, "refreshTokenExpiry", 7L);
  }

  @Test
  void registerShouldThrowWhenEmailExists() {
    RegisterRequest request = new RegisterRequest("a@b.com", "password123", UserRoles.CUSTOMER);
    when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

    assertThrows(DuplicateResourceException.class, () -> authService.register(request));
  }

  @Test
  void registerShouldPersistUserRoleWalletAndRefreshToken() {
    RegisterRequest request = new RegisterRequest("a@b.com", "password123", null);
    Role role = Role.builder().name("CUSTOMER").build();
    role.setId(2L);
    User savedUser = User.builder().email("a@b.com").password("enc").build();
    savedUser.setId(10L);
    UserRole savedUserRole = UserRole.builder().user(savedUser).role(role).build();

    when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
    when(passwordEncoder.encode("password123")).thenReturn("enc");
    when(userRepository.save(any(User.class))).thenReturn(savedUser);
    when(roleRepository.findByName("CUSTOMER")).thenReturn(Optional.of(role));
    when(userRoleRepository.save(any(UserRole.class))).thenReturn(savedUserRole);
    when(jwtService.generateAccessToken(savedUser, List.of("CUSTOMER"))).thenReturn("access");
    when(jwtService.generateRefreshToken()).thenReturn("refresh");

    AuthResponse response = authService.register(request);

    assertEquals("access", response.accessToken());
    assertEquals("refresh", response.refreshToken());
    verify(walletAccountRepository).save(any());
    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void loginShouldThrowWhenPasswordMismatch() {
    User user = User.builder().email("a@b.com").password("enc").enabled(true).build();
    user.setId(5L);
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong-password", "enc")).thenReturn(false);

    assertThrows(
        AuthenticationException.class, () -> authService.login(new LoginRequest("a@b.com", "wrong-password")));
  }

  @Test
  void registerShouldThrowWhenRoleIsMissing() {
    RegisterRequest request = new RegisterRequest("role-missing@b.com", "password123", null);
    User savedUser = User.builder().email("role-missing@b.com").password("enc").build();
    savedUser.setId(12L);

    when(userRepository.existsByEmail("role-missing@b.com")).thenReturn(false);
    when(passwordEncoder.encode("password123")).thenReturn("enc");
    when(userRepository.save(any(User.class))).thenReturn(savedUser);
    when(roleRepository.findByName("CUSTOMER")).thenReturn(Optional.empty());

    assertThrows(com.equitycart.commons.exception.ResourceNotFoundException.class, () -> authService.register(request));
  }

  @Test
  void loginShouldThrowWhenUserNotFound() {
    when(userRepository.findByEmail("missing@b.com")).thenReturn(Optional.empty());

    assertThrows(
        AuthenticationException.class,
        () -> authService.login(new LoginRequest("missing@b.com", "password123")));
  }

  @Test
  void loginShouldThrowWhenAccountDisabled() {
    User user =
        User.builder().email("a@b.com").password("enc").enabled(false).accountLocked(false).build();
    user.setId(15L);
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("password123", "enc")).thenReturn(true);

    assertThrows(
        com.equitycart.commons.exception.AccountDisabledException.class,
        () -> authService.login(new LoginRequest("a@b.com", "password123")));
  }

  @Test
  void loginShouldThrowWhenNoRolesAssigned() {
    User user =
        User.builder().email("a@b.com").password("enc").enabled(true).accountLocked(false).build();
    user.setId(16L);
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("password123", "enc")).thenReturn(true);
    when(userRoleRepository.findByUserId(16L)).thenReturn(List.of());

    assertThrows(
        com.equitycart.commons.exception.ResourceNotFoundException.class,
        () -> authService.login(new LoginRequest("a@b.com", "password123")));
  }

  @Test
  void loginShouldReturnTokensWhenCredentialsAndRolesAreValid() {
    Role role = Role.builder().name("CUSTOMER").build();
    User user =
        User.builder().email("ok@b.com").password("enc").enabled(true).accountLocked(false).build();
    user.setId(17L);
    UserRole userRole = UserRole.builder().user(user).role(role).build();

    when(userRepository.findByEmail("ok@b.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("password123", "enc")).thenReturn(true);
    when(userRoleRepository.findByUserId(17L)).thenReturn(List.of(userRole));
    when(jwtService.generateAccessToken(user, List.of("CUSTOMER"))).thenReturn("access-2");
    when(jwtService.generateRefreshToken()).thenReturn("refresh-2");

    AuthResponse response = authService.login(new LoginRequest("ok@b.com", "password123"));

    assertEquals("access-2", response.accessToken());
    assertEquals("refresh-2", response.refreshToken());
    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void refreshShouldThrowWhenTokenRevoked() {
    User user = User.builder().email("a@b.com").password("enc").build();
    user.setId(7L);
    RefreshToken token =
        RefreshToken.builder()
            .token("rt")
            .user(user)
            .revoked(true)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    when(refreshTokenRepository.findByToken("rt")).thenReturn(Optional.of(token));

    assertThrows(AuthenticationException.class, () -> authService.refreshToken(new RefreshRequest("rt")));
  }

  @Test
  void refreshShouldThrowWhenTokenMissing() {
    when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());
    assertThrows(AuthenticationException.class, () -> authService.refreshToken(new RefreshRequest("missing")));
  }

  @Test
  void refreshShouldThrowWhenTokenExpired() {
    User user = User.builder().email("a@b.com").password("enc").build();
    user.setId(8L);
    RefreshToken token =
        RefreshToken.builder()
            .token("rt-expired")
            .user(user)
            .revoked(false)
            .expiresAt(LocalDateTime.now().minusDays(1))
            .build();
    when(refreshTokenRepository.findByToken("rt-expired")).thenReturn(Optional.of(token));

    assertThrows(AuthenticationException.class, () -> authService.refreshToken(new RefreshRequest("rt-expired")));
  }

  @Test
  void refreshShouldThrowWhenNoRolesAssigned() {
    User user = User.builder().email("a@b.com").password("enc").build();
    user.setId(9L);
    RefreshToken token =
        RefreshToken.builder()
            .token("rt-ok")
            .user(user)
            .revoked(false)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    when(refreshTokenRepository.findByToken("rt-ok")).thenReturn(Optional.of(token));
    when(userRoleRepository.findByUserId(9L)).thenReturn(List.of());

    assertThrows(
        com.equitycart.commons.exception.ResourceNotFoundException.class,
        () -> authService.refreshToken(new RefreshRequest("rt-ok")));
    verify(refreshTokenRepository).save(token);
  }

  @Test
  void refreshShouldRotateTokenAndReturnNewPair() {
    User user = User.builder().email("a@b.com").password("enc").build();
    user.setId(10L);
    Role role = Role.builder().name("ADMIN").build();
    UserRole userRole = UserRole.builder().user(user).role(role).build();
    RefreshToken token =
        RefreshToken.builder()
            .token("rt-valid")
            .user(user)
            .revoked(false)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();

    when(refreshTokenRepository.findByToken("rt-valid")).thenReturn(Optional.of(token));
    when(userRoleRepository.findByUserId(10L)).thenReturn(List.of(userRole));
    when(jwtService.generateAccessToken(user, List.of("ADMIN"))).thenReturn("access-new");
    when(jwtService.generateRefreshToken()).thenReturn("refresh-new");

    AuthResponse response = authService.refreshToken(new RefreshRequest("rt-valid"));

    assertEquals("access-new", response.accessToken());
    assertEquals("refresh-new", response.refreshToken());
    assertEquals(true, token.isRevoked());
  }
}
