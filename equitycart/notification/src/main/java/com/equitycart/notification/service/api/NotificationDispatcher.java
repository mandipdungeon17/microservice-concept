package com.equitycart.notification.service.api;

import com.equitycart.commons.event.NotificationEvent;

/**
 * Dispatches a notification event through the configured delivery channel and persists the result.
 *
 * <p>Implementations resolve the active channel strategy at runtime, build a human-readable
 * subject/body from the event data, invoke the channel, and save a {@code NotificationLog}
 * recording the outcome (SENT or FAILED).
 */
public interface NotificationDispatcher {

  void dispatch(NotificationEvent event);
}
