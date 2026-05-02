package com.equitycart.order.cart.service.api;

import com.equitycart.order.cart.dto.AddToCartRequest;
import com.equitycart.order.cart.dto.CartResponse;

/**
 * Service interface for shopping cart operations. Defines add, remove, retrieve, and clear
 * capabilities for a Redis-backed user cart.
 */
public interface CartService {

  /**
   * Adds or updates an item in the user's cart. If the product already exists, it overwrites the
   * previous entry.
   *
   * @param userId the user identifier
   * @param request the item details (productId, quantity, price snapshot)
   */
  void addItem(String userId, AddToCartRequest request);

  /**
   * Removes a specific product from the user's cart.
   *
   * @param userId the user identifier
   * @param productId the product to remove
   */
  void removeItem(String userId, Long productId);

  /**
   * Retrieves the full cart for a user including all items, total, and expiry time.
   *
   * @param userId the user identifier
   * @return the cart response with items, total, and TTL-based expiry timestamp
   */
  CartResponse getCart(String userId);

  /**
   * Clears all items from the user's cart by deleting the Redis key.
   *
   * @param userId the user identifier
   */
  void clearCart(String userId);
}
