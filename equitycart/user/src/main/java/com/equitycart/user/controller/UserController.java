package com.equitycart.user.controller;

import com.equitycart.user.service.api.UserService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for authenticated user operations such as logout and admin-only test endpoints.
 * All endpoints are under {@code /api/user} and require a valid JWT token.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserController {

  private static final Logger log = LogManager.getLogger(UserController.class);

  private final UserService userService;

  /**
   * Logs out the current user by revoking all their active refresh tokens.
   *
   * @param authentication the Spring Security authentication containing the user ID
   * @return {@code 200 OK} on successful logout
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    log.info("POST /api/user/logout - user id: {}", userId);
    // Alternative way to get userId from SecurityContextHolder if Authentication is not injected
    //   Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    userService.logout(userId);
    return ResponseEntity.ok().build();
  }

  /**
   * Admin-only test endpoint to verify role-based access control.
   *
   * @return {@code 200 OK} with a confirmation message
   */
  @GetMapping("/admin/test")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<String> adminTest() {
    log.info("GET /api/user/admin/test - admin access verified");
    return ResponseEntity.ok("Admin access granted");
  }
}
