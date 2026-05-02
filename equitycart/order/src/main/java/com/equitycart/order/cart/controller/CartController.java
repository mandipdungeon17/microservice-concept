package com.equitycart.order.cart.controller;

import com.equitycart.order.cart.dto.AddToCartRequest;
import com.equitycart.order.cart.dto.CartResponse;
import com.equitycart.order.cart.service.api.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for shopping cart operations. Provides endpoints for adding, removing,
 * retrieving, and clearing cart items for a given user.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cart")
public class CartController {

  private static final Logger log = LogManager.getLogger(CartController.class);

  private final CartService cartService;

  @PostMapping("/items")
  @ResponseStatus(HttpStatus.CREATED)
  public void addItem(Authentication authentication, @Valid @RequestBody AddToCartRequest request) {
    String userId = authentication.getName();
    log.info("POST /api/cart/{}/items - adding product: {}", userId, request.productId());
    cartService.addItem(userId, request);
  }

  @DeleteMapping("/items/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeItem(Authentication authentication, @PathVariable Long productId) {
    String userId = authentication.getName();
    log.info("DELETE /api/cart/{}/items/{} - removing item", userId, productId);
    cartService.removeItem(userId, productId);
  }

  @GetMapping
  public CartResponse getCart(Authentication authentication) {
    String userId = authentication.getName();
    log.debug("GET /api/cart/{} - retrieving cart", userId);
    return cartService.getCart(userId);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearCart(Authentication authentication) {
    String userId = authentication.getName();
    log.info("DELETE /api/cart/{} - clearing cart", userId);
    cartService.clearCart(userId);
  }
}
