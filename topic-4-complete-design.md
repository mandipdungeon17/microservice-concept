# Topic 4: Price Alert Watchlist - Complete Design & Implementation

**Status:** In Design (Complete)  
**Date:** 2026-08-15  
**Deliverable:** 15 classes + comprehensive documentation

---

## Executive Summary

Topic 4 introduces **asynchronous alert evaluation** - a shift from transactional patterns (Topics 1-3) to scheduled, event-driven patterns. Users define price alert rules; the system evaluates them every 5 seconds and notifies via multiple channels (WebSocket, Email, SMS, In-App) without spamming.

**Key Learning:** Cooldown logic to prevent notification spam while maintaining freshness. Scheduled evaluation as alternative to reactive streaming.

---

## Implementation Checklist

- [ ] Create `alert/enums/` directory with AlertCondition, AlertEventType, NotificationChannel
- [ ] Create `alert/entity/` with PriceAlert, AlertAuditLog
- [ ] Create `alert/repository/` with PriceAlertRepository, AlertAuditLogRepository
- [ ] Create `alert/service/` with AlertConditionEvaluator, PriceAlertService, AlertEvaluationService, NotificationService
- [ ] Create `alert/controller/` with PriceAlertController
- [ ] Create `alert/dto/` with 4 DTOs
- [ ] Create `alert/event/` with event classes
- [ ] Create `config/AlertSchedulerConfiguration`
- [ ] Add Liquibase/Flyway migrations for 2 tables
- [ ] Implement NotificationService handlers (WebSocket, Email, SMS, In-App)
- [ ] Implement PortfolioPriceService methods (getCurrentPrice, getPreviousPrice)
- [ ] Run compilation: `./gradlew :portfolio:compileJava`
- [ ] Manual E2E testing (10 scenarios)
- [ ] Add JavaDoc to all classes
- [ ] Update learning files with Topic 4 concepts

---

## 15 Classes Overview

**Enums (3):**
- AlertCondition: ABOVE, BELOW, BETWEEN, CROSSING
- AlertEventType: CREATED, TRIGGERED, COOLDOWN_SKIPPED, CONDITION_NOT_MET, EVALUATION_ERROR, NOTIFICATION_FAILED, DEACTIVATED, REACTIVATED
- NotificationChannel: WEBSOCKET, EMAIL, SMS, IN_APP

**Entities (2):**
- PriceAlert: Alert rule with condition, thresholds, cooldown, channels, lifecycle hooks
- AlertAuditLog: Event history for debugging and compliance

**Repositories (2):**
- PriceAlertRepository: 8 custom queries (findByUserIdAndActiveTrue, findCooldownExpiredAlerts, etc.)
- AlertAuditLogRepository: 6 queries for audit trail access

**Services (5):**
- AlertConditionEvaluator: Pure logic for all 4 condition types
- PriceAlertService: CRUD operations, validation, idempotency, quota (max 50/user)
- AlertEvaluationService: @Scheduled loop (every 5s), evaluation orchestration
- NotificationService: Multi-channel dispatcher (WebSocket, Email, SMS, In-App)

**Controller (1):**
- PriceAlertController: 6 REST endpoints (CRUD + history)

**DTOs (4):**
- CreatePriceAlertRequest, UpdatePriceAlertRequest, PriceAlertResponse, AlertAuditLogResponse
- Internal: AlertNotificationRequest

**Events (2):**
- PriceAlertCreatedEvent: Published on alert creation
- PriceAlertTriggeredEvent: Published on alert trigger

**Config (1):**
- AlertSchedulerConfiguration: Thread pool setup

**SQL Schemas (2 tables):**
- price_alerts: User's alert rules with 3 indexes
- alert_audit_logs: Event history with 3 indexes

---

## Evaluation Algorithm (Core Logic)

```
Every 5 seconds:
  1. Fetch all active alerts from DB
  2. Group by ticker symbol
  3. For each ticker group:
     a. Fetch current market price (once per ticker, cached)
     b. Fetch previous market price (for CROSSING detection)
     c. For each alert on this ticker:
        - Evaluate condition: does current price match alert criteria?
        - Check cooldown: enough time since last alert trigger?
        - If condition=TRUE AND cooldown_expired=TRUE:
          * Send notification via all channels
          * Update alert.lastAlertSentAt = now
          * Save to DB
          * Publish event (for metrics/analytics)
          * Record audit log TRIGGERED
        - Else if condition=TRUE AND cooldown_active=TRUE:
          * Log COOLDOWN_SKIPPED (no notification, no alert update)
        - Else (condition=FALSE):
          * Log CONDITION_NOT_MET (normal case)
```

## Cooldown Logic Walkthrough

**Scenario:** User creates alert "AAPL > $150" with cooldown=60min

| Time | Price | Condition | Cooldown | Action |
|------|-------|-----------|----------|--------|
| 10:00 | $149 | FALSE | N/A | No trigger (condition false) |
| 10:05 | $151 | TRUE | expired | **TRIGGER** → notify, lastAlertSentAt=10:05 |
| 10:10 | $151 | TRUE | active (55min left) | Skip (cooldown prevents) |
| 10:30 | $151 | TRUE | active (35min left) | Skip (cooldown prevents) |
| 11:05 | $151 | TRUE | expired (60min elapsed) | **TRIGGER AGAIN** → notify, lastAlertSentAt=11:05 |
| 11:10 | $149 | FALSE | expired | No trigger (condition false) |
| 13:00 | $152 | TRUE | expired | **TRIGGER AGAIN** → notify, lastAlertSentAt=13:00 |

**Result:** User gets 1 alert per 60 minutes, not 12 alerts/hour (one per 5-sec eval cycle)

---

## 10 Manual E2E Test Scenarios

1. **Happy Path**: Create AAPL>$150, price $149→$151, WebSocket notification arrives in <5s
2. **Cooldown Prevention**: Repeat alert at +2min, verify COOLDOWN_SKIPPED (no notify)
3. **Cooldown Expiration**: At +61min, alert triggers again (second time, new lastAlertSentAt)
4. **Condition Not Met**: Price stays below threshold, verify no notification sent
5. **CROSSING Condition**: Create CROSSING $150, price $149.80→$150.50 (crosses), verify 1 notify, price $150.50→$151 (stays above), verify no second notify
6. **Duplicate Prevention**: Create AAPL>$150 twice, verify 409 Conflict on second
7. **Multi-Channel**: Create with [WEBSOCKET, EMAIL, SMS], trigger, verify all 3 channels receive
8. **Quota Enforcement**: Create 50 alerts (quota), 51st returns 429 Too Many Requests
9. **Audit Trail**: GET /api/portfolio/alerts/{alertId}/history, verify events ordered by timestamp
10. **Update & Soft Delete**: Update cooldown to 30min (immediate effect), delete (active=false), verify evaluator skips on next cycle

---

## Key Concepts

**Scheduled vs Reactive:**
- **Scheduled (5s):** Query all alerts, evaluate, predictable CPU load
- **Reactive (streaming):** Trigger on price message, lower latency, backpressure complexity
- **Topic 4 uses Scheduled** for simplicity; can evolve to reactive if sub-second latency needed

**Optimistic Locking:**
- Multiple evaluator threads can update same alert simultaneously
- @Version field prevents conflicts
- Retry logic on OptimisticLockException

**Soft Delete:**
- active=false instead of hard delete
- Preserves audit trail
- User can reactivate later

**Per-Channel Error Resilience:**
- If WebSocket fails, still try Email, SMS, In-App
- Don't fail-fast; try all channels
- If ≥1 channel succeeds, alert considered "sent"

**Stateless Evaluator:**
- No in-memory cache; read from DB each cycle
- Scales horizontally
- Multiple instances can run in parallel (no coordination needed)

---

## Design Trade-Offs

| Decision | Why This Way | Trade-Off |
|----------|--------------|-----------|
| **Scheduled (5s) vs Reactive** | Simpler, predictable, acceptable latency | 5s delay vs backpressure handling |
| **Optimistic Locking** | Better throughput | Retry logic on exceptions |
| **Soft Delete** | Compliance + audit trail | Storage cost |
| **Per-Channel Resilience** | User prefers some notify over zero | Complex error handling |
| **Stateless Evaluator** | Horizontal scalability | DB load per cycle |
| **Cooldown (not nextEligibleAt)** | Simpler, changing cooldown is immediate | Computed field |
| **Audit Log** | Compliance + debugging | Storage cost |
| **Quota (50/user)** | DOS prevention | Limited flexibility |

---

## Phase 10 Status Update

| Topic | Status | Next |
|-------|--------|------|
| Topic 1 | ✅ Complete | Docs closeout |
| Topic 2 | ✅ Complete | E2E validation |
| Topic 3 | ✅ Complete | Production ready |
| **Topic 4** | **✅ Designed** | **Implement 15 classes** |
| Topic 5-10 | Not Started | Future |

---

**Prepared:** 2026-08-15  
**Deliverable:** Complete design doc + 15 class implementations (in chat)  
**Ready for:** User implementation phase
