package com.equitycart.commons.event;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Nested event DTO representing a single line item within an {@link OrderDeliveredEvent}. Carries
 * the product and pricing snapshot needed for stock-back reward calculation.
 *
 * <p>The consumer uses {@code productId} to look up Brand → BrandTickerMapping → tickerSymbol +
 * stockBackPercentage. The {@code subTotal} field is the basis for reward dollar value: {@code
 * subTotal × stockBackPercentage / 100}.
 *
 * <p><b>Record equivalent:</b> {@code record OrderItemEvent(Long productId, String productName,
 * Integer quantity, BigDecimal priceAtPurchase, BigDecimal subTotal) {}} Would eliminate: no-arg
 * constructor, all getters/setters, equals/hashCode, toString (~60 lines → 2 lines).
 */
public class OrderItemEvent {
  private Long productId;
  private String productName;
  private Integer quantity;
  private BigDecimal priceAtPurchase;
  private BigDecimal subtotal;

  /** No-arg constructor required by Jackson's default deserialization strategy. */
  public OrderItemEvent() {}

  /** All-args constructor for producer-side event creation. */
  public OrderItemEvent(
      Long productId,
      String productName,
      Integer quantity,
      BigDecimal priceAtPurchase,
      BigDecimal subtotal) {
    this.productId = productId;
    this.productName = productName;
    this.quantity = quantity;
    this.priceAtPurchase = priceAtPurchase;
    this.subtotal = subtotal;
  }

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long productId) {
    this.productId = productId;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getPriceAtPurchase() {
    return priceAtPurchase;
  }

  public void setPriceAtPurchase(BigDecimal priceAtPurchase) {
    this.priceAtPurchase = priceAtPurchase;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public void setSubtotal(BigDecimal subtotal) {
    this.subtotal = subtotal;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OrderItemEvent that)) return false;
    return Objects.equals(productId, that.productId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(productId);
  }

  @Override
  public String toString() {
    return "OrderItemEvent{"
        + "productId="
        + productId
        + ", productName='"
        + productName
        + '\''
        + ", quantity="
        + quantity
        + ", priceAtPurchase="
        + priceAtPurchase
        + ", subtotal="
        + subtotal
        + '}';
  }
}
