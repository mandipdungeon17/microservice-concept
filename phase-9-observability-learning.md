# Phase 9 — Observability Deep Dive (Implementation + Learning Record)

> This file captures Phase 9 end-to-end: architecture, implementation decisions, debugging history, blocked items, fallbacks, and interview-focused reasoning.

---

## 1) Phase 9 Objective

Make the microservice system operationally observable through:

- structured logs
- metrics
- distributed traces
- actionable alerts

Target outcome: engineers can detect incidents early, triage quickly, and explain system behavior under load/failure.

---

## 2) Final Implemented Scope

## 2.1 Structured Logging

- Per-service `log4j2-spring.xml` with JSON logging
- Console + rolling file appenders
- Correlation context included in logs

## 2.2 Metrics

- Actuator + Prometheus scrape endpoints
- Prometheus scrape config for all relevant services
- Custom business metrics for order, portfolio, notification flows

## 2.3 Dashboards

- Grafana service integrated in infra compose
- Provisioned Prometheus datasource/dashboard configs

## 2.4 Distributed Tracing

- Micrometer tracing with Zipkin exporter endpoint
- Zipkin service integrated for trace visualization

## 2.5 Alerting

- Service down alert
- Error-rate alert
- p99 latency alert

---

## 3) Architecture View

```
Client → API Gateway → Business Services
             │              │
             │              ├─ Structured JSON logs (with correlation context)
             │              ├─ Micrometer metrics (/actuator/prometheus)
             │              └─ Trace spans export
             │
             ├─ Prometheus ← scrapes metrics from services
             ├─ Grafana    ← dashboards + alert rules over Prometheus
             └─ Zipkin     ← receives and visualizes traces
```

---

## 4) What This Achieves in Real Companies

- **SRE/Platform teams**: define SLIs/SLOs and enforce alert policies.
- **Backend teams**: detect regressions (latency/error spikes) post-release.
- **Incident response**: correlate alert → metric spike → trace path → log evidence.
- **Product reliability**: quantify user impact and MTTR improvements.

This mirrors common production stacks:

- Micrometer + Prometheus for metrics
- Grafana for dashboards/alerts
- OpenTelemetry/Micrometer tracing to Zipkin/Jaeger/Tempo
- Structured logs shipped to centralized backends when policy permits

---

## 5) Problems Faced, Root Cause, and Resolution

## 5.1 `/actuator/prometheus` not available

- **Root cause:** missing Prometheus registry dependency.
- **Fix:** add `micrometer-registry-prometheus`.
- **Learning:** actuator exposure does not create endpoint beans by itself.

## 5.2 Split-compose networking failures

- **Root cause:** infra/app compose files on isolated default networks.
- **Fix:** shared external network in both files + startup-time network creation.
- **Learning:** cross-compose DNS requires explicit network strategy.

## 5.3 Alert queries in NoData state

- **Root cause:** query/label/window mismatch between dashboard and alert expression.
- **Fix:** validate each rule in Explore and adjust query shape/NoData policy.
- **Learning:** dashboard success does not guarantee alert-query correctness.

## 5.4 EFK/Fluentd image pull blocked

- **Root cause:** enterprise Zscaler policy blocking `docker.elastic.co`.
- **Fix:** documented as environmental blocker; adopted fallback.
- **Learning:** treat policy constraints as first-class architecture constraints.

---

## 6) Skipped / Deferred Items (Explicit)

## 6.1 Centralized EFK/Fluentd stack

- **Status:** deferred due to enterprise network restrictions.
- **Fallback adopted:** structured per-service logs + `core-loglens`.
- **Future path:** enable once registry access is allowlisted/proxied.

---

## 7) Alternative Approaches Considered

1. **ELK/EFK centralized logging**  
   Strong search/retention pipeline; blocked in this environment.

2. **OpenTelemetry collector + vendor backend**  
   Useful at scale; higher setup complexity for current phase.

3. **App-only logging without metrics/traces**  
   Rejected: insufficient for SLO-grade observability.

---

## 8) Phase 9 History (Condensed Timeline)

1. Logging foundation validated and standardized per service.
2. Prometheus added; endpoint and scraping issues debugged.
3. Grafana brought up and connected to Prometheus.
4. Zipkin tracing integrated with Boot 3 style config alignment.
5. Custom business metrics inserted into order/portfolio/notification services.
6. Alert rules configured and iteratively corrected.
7. EFK/Fluentd attempt blocked; fallback finalized and documented.

---

## 9) Interview-Focused Deep Learning

## 9.1 Correlation ID vs Trace ID

- Correlation ID: log grouping key
- Trace ID: distributed causal timeline key
- Both are useful; neither fully replaces the other

## 9.2 Why p99 alerts over average latency

- Average hides tail pain
- p99 captures worst-user experience and early degradation

## 9.3 Where to instrument business metrics

- Instrument at business outcome boundaries
- Avoid high-cardinality labels (userId/orderId/sessionId)
- Guard idempotent paths to avoid metric pollution

## 9.4 Why alerts are part of implementation (not post-work)

- Without alerts, observability is passive
- Alerts operationalize SLO expectations and reduce incident detection latency

---

## 10) Completion Criteria (What “Done” Means)

- Metrics are scraped and visible
- Dashboards show live service signals
- Traces show multi-service call paths
- Alerts can transition through firing/recovery states
- Logs are structured and correlated across services
- Known blockers/fallbacks are documented with rationale

---

## 11) Final Status

Phase 9 is functionally complete with one documented external blocker:

- **Blocked:** EFK/Fluentd image access (network policy)
- **Implemented fallback:** structured logs + `core-loglens`

All other observability outcomes are implemented and validated.
