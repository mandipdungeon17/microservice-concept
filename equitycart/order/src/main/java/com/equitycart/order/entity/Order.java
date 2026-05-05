package com.equitycart.order.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.order.enums.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a customer order containing line items, shipping details, and payment information.
 * Uses {@code @Table(name = "orders")} because "order" is a SQL reserved keyword.
 */
@Entity
@Table(name = "orders") // "order" is a SQL reserved keyword!
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Order extends BaseEntity {

  @Column(nullable = false)
  private Long userId;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private OrderStatus status;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal totalAmount;

  @Column(unique = true, nullable = false)
  private String idempotencyKey;

  @Column(nullable = false)
  private String shippingAddress;

  private String paymentMethod;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  @Builder.Default
  private List<OrderItem> items = new ArrayList<>();

  // Helper method to maintain bidirectional relationship
  public void addItem(OrderItem orderItem) {
    items.add(orderItem);
    orderItem.setOrder(this);
  }
}
