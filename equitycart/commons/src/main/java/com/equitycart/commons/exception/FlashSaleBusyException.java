package com.equitycart.commons.exception;

/**
 * Thrown when flash-sale purchase is temporarily not executable.
 *
 * <p>Raised for cases such as:
 *
 * <ul>
 *   <li>distributed product lock could not be acquired in bounded retries
 *   <li>flash-sale time window is inactive
 * </ul>
 */
public class FlashSaleBusyException extends RuntimeException {

  public FlashSaleBusyException(String message) {
    super(message);
  }
}
