package com.equitycart.user.service.impl;

import com.equitycart.commons.exception.AccountDisabledException;
import com.equitycart.commons.exception.AuthenticationException;
import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.user.dto.AuthResponse;
import com.equitycart.user.dto.LoginRequest;
import com.equitycart.user.dto.RefreshRequest;
import com.equitycart.user.dto.RegisterRequest;
import com.equitycart.user.entity.RefreshToken;
import com.equitycart.user.entity.Role;
import com.equitycart.user.entity.User;
import com.equitycart.user.entity.UserRole;
import com.equitycart.user.entity.WalletAccount;
import com.equitycart.user.enums.UserRoles;
import com.equitycart.user.repository.RefreshTokenRepository;
import com.equitycart.user.repository.RoleRepository;
import com.equitycart.user.repository.UserRepository;
import com.equitycart.user.repository.UserRoleRepository;
import com.equitycart.user.repository.WalletAccountRepository;
import com.equitycart.user.service.api.AuthService;
import com.equitycart.user.service.api.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
/* Lombok annotation to generate a constructor with required arguments (final fields)
    This allows for dependency injection of the repositories and password encoder.
    @Autowired is not needed when using constructor injection with Spring,
    and Lombok's @RequiredArgsConstructor simplifies this process.
 */
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    @Value("${jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;
    private final JwtService jwtService;

    @Transactional
    @Override
    public AuthResponse register(RegisterRequest request) {
        if(userRepository.existsByEmail(request.email())){
            throw new DuplicateResourceException("Email already registered");
        }
        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.builder().email(request.email()).password(encodedPassword).build();
        User savedUser = userRepository.save(user);

        Optional<Role> role = roleRepository.findByName(UserRoles.CUSTOMER.name());
        if(role.isEmpty()) throw new ResourceNotFoundException("Default role CUSTOMER not found");

        UserRole userRole = UserRole.builder().user(savedUser).role(role.get()).build();
        userRoleRepository.save(userRole);

        WalletAccount walletAccount = WalletAccount.builder().user(savedUser).build();
        walletAccountRepository.save(walletAccount);

        return generateAuthAndRefreshTokens(savedUser, List.of(userRole.getRole().getName()));
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        Optional<User> optionalUser = userRepository.findByEmail(request.email());
        if(optionalUser.isEmpty())
            throw  new AuthenticationException("Invalid email or password");

        User userEntity = optionalUser.get();

        if(!passwordEncoder.matches(request.password(), userEntity.getPassword()))
            throw new AuthenticationException("Invalid email or password");

        if(userEntity.isAccountLocked() || !userEntity.isEnabled()){
            throw new AccountDisabledException("Account is locked or disabled");
        }

        List<UserRole> userRoles = userRoleRepository.findByUserId(userEntity.getId());

        if(userRoles.isEmpty())
            throw new ResourceNotFoundException("User has no assigned roles");

        List<String> roles = userRoles.stream().map(u -> u.getRole().getName()).toList();

        return generateAuthAndRefreshTokens(userEntity, roles);
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(RefreshRequest request) {
        Optional<RefreshToken> optionalRefreshToken = refreshTokenRepository.findByToken(request.refreshToken());

        if(optionalRefreshToken.isEmpty()) throw new AuthenticationException("Invalid refresh token");

        RefreshToken refreshTokenEntity = optionalRefreshToken.get();

        if(refreshTokenEntity.isRevoked() || refreshTokenEntity.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new AuthenticationException("Refresh token has been revoked or expired");

        refreshTokenEntity.setRevoked(true);
        refreshTokenRepository.save(refreshTokenEntity);

        List<UserRole> userRoles = userRoleRepository.findByUserId(refreshTokenEntity.getUser().getId());

        if(userRoles.isEmpty())
            throw new ResourceNotFoundException("User has no assigned roles");

        List<String> roles = userRoles.stream().map(u -> u.getRole().getName()).toList();

        return generateAuthAndRefreshTokens(refreshTokenEntity.getUser(), roles);
    }

    private AuthResponse generateAuthAndRefreshTokens(User user, List<String> roles){
        String accessToken = jwtService.generateAccessToken(user, roles);
        String refreshToken = jwtService.generateRefreshToken();

        RefreshToken refreshTokenEntity =
                RefreshToken.builder()
                .user(user).token(refreshToken)
                .expiresAt(LocalDateTime.now().plusDays(refreshTokenExpiry)).build();

        refreshTokenRepository.save(refreshTokenEntity);

        return new AuthResponse(accessToken, refreshToken);
    }
}
