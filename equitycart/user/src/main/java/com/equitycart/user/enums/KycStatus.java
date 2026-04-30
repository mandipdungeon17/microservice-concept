package com.equitycart.user.enums;

/**
 * Enumeration representing the lifecycle stages of a KYC verification. A KYC submission starts as
 * {@code PENDING} and transitions to either {@code VERIFIED} or {@code REJECTED} after review.
 */
public enum KycStatus {
  PENDING,
  VERIFIED,
  REJECTED
}
