package com.equitycart.user.service.impl;

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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
/* Lombok annotation to generate a constructor with required arguments (final fields)
    This allows for dependency injection of the repositories and password encoder.
    @Autowired is not needed when using constructor injection with Spring,
    and Lombok's @RequiredArgsConstructor simplifies this process.
 */
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;

    @Transactional
    @Override
    public AuthResponse register(RegisterRequest request) {
        if(userRepository.existsByEmail(request.email())){
            throw new RuntimeException("Email already registered");
        }
        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.builder().email(request.email()).password(encodedPassword).build();
        User savedUser = userRepository.save(user);

        Optional<Role> role = roleRepository.findByName(UserRoles.CUSTOMER.name());
        if(role.isEmpty()) throw new RuntimeException("Default role CUSTOMER not found");

        UserRole userRole = UserRole.builder().user(savedUser).role(role.get()).build();
        userRoleRepository.save(userRole);

        WalletAccount walletAccount = WalletAccount.builder().user(savedUser).build();
        walletAccountRepository.save(walletAccount);

        return new AuthResponse("TODO_ACCESS", "TODO_REFRESH");
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        Optional<User> optionalUser = userRepository.findByEmail(request.email());
        if(optionalUser.isEmpty())
            throw  new RuntimeException("User doesn't exist");

        User userEntity = optionalUser.get();

        if(!passwordEncoder.matches(request.password(), userEntity.getPassword()))
            throw new RuntimeException("Invalid email or password");

        if(userEntity.isAccountLocked() || !userEntity.isEnabled()){
            throw new RuntimeException("Account is locked or disabled");
        }
        return new AuthResponse("TODO_ACCESS", "TODO_REFRESH");
    }

    @Override
    public AuthResponse refreshToken(RefreshRequest request) {
        Optional<RefreshToken> optionalRefreshToken = refreshTokenRepository.findByToken(request.refreshToken());

        if(optionalRefreshToken.isEmpty()) throw new RuntimeException("Invalid refresh token");

        RefreshToken refreshTokenEntity = optionalRefreshToken.get();

        if(refreshTokenEntity.isRevoked() || refreshTokenEntity.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new RuntimeException("Refresh token has been revoked or expired");

        return new AuthResponse("TODO_ACCESS", "TODO_REFRESH");
    }
}
