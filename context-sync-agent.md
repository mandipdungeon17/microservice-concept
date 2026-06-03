# Context Sync Agent — Pre-Implementation Checklist

> **Purpose**: Before starting ANY implementation task, analyze these files systematically to understand project state, avoid context sync issues, and maintain consistency with prior decisions.
>
> **When to use**: At the start of every session OR before starting a new phase/step.
> **Time investment**: 5-10 minutes of analysis per session prevents hours of debugging.

---

## 📋 Files to Analyze (In Priority Order)

### 1. **progress.md** — Project State Tracking [ALWAYS FIRST]

- **What to check**:
  - Current phase (IN PROGRESS vs COMPLETE vs PENDING)
  - Which steps are done vs remaining
  - Last session's date and what was accomplished
  - Known issues or blockers from prior work
- **Why**: Tells you exactly where the project stands and what to do next
- **Time**: 2-3 minutes
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\progress.md`

### 2. **equitycart-roadmap.md** — Macro Requirements

- **What to check**:
  - Full 10-phase roadmap overview
  - This phase's deliverables (compare to progress.md to spot gaps)
  - Dependencies on prior phases (what MUST be done before this phase)
- **Why**: Prevents local optimization that breaks global requirements
- **Time**: 2-3 minutes
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\equitycart-roadmap.md`

### 3. **learning-instructor-agent.md** — Agent Responsibilities & Rules

- **What to check**:
  - Your role and constraints (what you MUST do, what you MUST NOT do)
  - Code ownership rules (agent writes only Javadoc/loggers/docs, student owns implementation)
  - Specification-only approach (never complete code blocks)
  - Documentation requirements (which files need Javadoc, logging consistency)
- **Why**: Ensures you don't violate learned workflow constraints
- **Time**: 1-2 minutes (re-skim sections relevant to current task)
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\learning-instructor-agent.md`

### 4. **project-development-prompt.md** — Vision & Standards

- **What to check**:
  - Project vision and learning goals
  - Tech stack decisions and WHY (not just WHAT)
  - Code quality standards (logging, error handling, testing approach)
  - Architecture principles (microservices separation, patterns used)
- **Why**: Aligns suggestions with established project culture
- **Time**: 2-3 minutes (search for relevant sections)
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\project-development-prompt.md`

### 5. **c:\Users\H504024\.claude\projects\c--Users-H504024-Documents-Docs-Mandip-Preparation-microservice-concept\memory\MEMORY.md** — Conversation Context

- **What to check**:
  - User feedback rules (what the user explicitly taught you)
  - Project status memories (ongoing initiatives, decisions made)
  - References to external systems (GitHub repos, Docker configs, etc.)
- **Why**: Avoids repeating mistakes the user already corrected
- **Time**: 2-3 minutes (review all entries, focus on feedback entries)
- **Location**: `.claude/projects/c--Users-.../memory/MEMORY.md`

### 6. **learning_log.md** — Phase-Specific Learnings

- **What to check**:
  - Roadblocks and issues FACED in this and prior phases
  - Concepts learned and why they matter
  - Common pitfalls to avoid
- **Why**: Learn from prior mistakes without repeating them
- **Time**: 2-3 minutes (search for current phase section)
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\learning_log.md`

### 7. **Reference Documentation** (Microservices, Security, SpringBoot patterns)

- **What to check**:
  - microservice-patterns.md → current architecture decisions, service boundaries, communication patterns
  - springboot-reference.md → Spring Boot version-specific gotchas, autoconfiguration, dependency management
  - security-reference.md → Security patterns used in project, known vulnerabilities, auth flow
- **Why**: Prevents re-inventing the wheel or breaking established patterns
- **Time**: 1-2 minutes per file (search for relevant sections)
- **Locations**:
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\microservice-patterns.md`
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\springboot-reference.md`
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\security-reference.md`

### 8. **Project Structure Understanding** — Mental Model

- **What to check**:
  - Current directory structure: modules, main app, config repo, test artifacts
  - Which services exist, what ports they run on
  - Database schemas (PostgreSQL vs MongoDB vs Redis usage)
  - Infrastructure dependencies (Kafka, Eureka, Config Server, Docker containers)
- **Why**: Prevents proposing solutions that conflict with existing structure
- **Time**: 2-3 minutes (visual scan)
- **Key locations**:
  ```
  microservice-concept/
  ├── equitycart/                    # Main application modules
  │   ├── app/                       # Monolithic app (until Step 4, then refactored)
  │   ├── commons/                   # Shared DTOs, entities, utilities
  │   ├── user/                      # User service
  │   ├── product/                   # Product service
  │   ├── order/                     # Order service
  │   ├── portfolio/                 # Portfolio service
  │   ├── market-data/               # Market data service
  │   ├── ledger/                    # Ledger service
  │   ├── notification/              # Notification service
  │   ├── discovery-server/          # Eureka (Phase 7)
  │   ├── config-server/             # Config Server (Phase 7)
  │   └── api-gateway/               # API Gateway (Phase 7)
  ├── equitycart-config/             # Separate config repo (Git-backed)
  ├── progress.md                    # THIS SESSION'S START POINT
  ├── learning_log.md                # Phase learnings
  ├── equitycart-roadmap.md          # Full roadmap
  └── ... (reference docs)
  ```

---

## 🔍 Quick Context Sync Flowchart

```
START IMPLEMENTATION SESSION
    ↓
[1] Read progress.md — What is the current phase/step?
    ↓
[2] Read equitycart-roadmap.md — What SHOULD I deliver?
    ↓
[3] Read learning-instructor-agent.md — What are MY constraints?
    ↓
[4] Read project-development-prompt.md — What's the vision?
    ↓
[5] Skim MEMORY.md — What feedback should I apply?
    ↓
[6] Skim learning_log.md (current phase) — What pitfalls exist?
    ↓
[7] Skim relevant reference docs — What patterns are established?
    ↓
[8] Mental map of project structure — Can my solution work here?
    ↓
IF ALL CLEAR → Proceed with task
    OR
IF CONFUSED → Ask user for clarification
```

---

## 🚨 Context Sync Red Flags

**Stop and ask the user if you notice**:

1. **progress.md doesn't mention this phase** → Task may be out of scope or future work
2. **equitycart-roadmap.md lists dependencies not yet COMPLETE** → Prerequisites missing
3. **MEMORY.md has a feedback rule contradicting your plan** → User taught you something different
4. **learning_log.md documents a known pitfall you're about to walk into** → Learn from history
5. **Reference docs show a different pattern than what you're proposing** → Breaking established patterns
6. **Current file structure doesn't match what you're implementing** → Architecture assumption wrong
7. **Two sessions ago you built X, but progress.md says it's PENDING** → Data integrity issue (ask user)

---

## 📝 Files to Create/Update When Work Is Done

After completing a phase/step, update **in this order**:

1. **progress.md** — Mark steps COMPLETE, record date, summarize what was done and issues faced
2. **learning_log.md** — Add roadblocks faced, concepts learned, lessons for next time
3. **Reference docs** (springboot-reference.md, microservice-patterns.md, etc.) — Add relevant learnings so others benefit
4. **Javadoc** in all new/modified Java files (per learning-instructor-agent.md rules)
5. **Logging statements** (use Log4j, not @Slf4j per project rules)
6. **MEMORY.md** (via memory files in .claude/projects/.../) — Record any user feedback or project decisions

---

## 💡 Pro Tips

- **Bookmark progress.md** — It's your north star. Check it 1st and last in every session.
- **Trust learning_log.md** — If it documents a roadblock, you're likely to hit it. Plan for it.
- **Use Grep** on .md files to search for keywords (e.g., "bootstrap", "Eureka", "YAML") rather than scrolling.
- **If context drifts**, re-read these 8 files in order. The answer will be in one of them.
- **When proposing fixes**, cite which document supports your approach (e.g., "per microservice-patterns.md, services communicate via OpenFeign").

---

## 📌 Last Updated

- **Date**: 2026-06-02
- **By**: Context Sync Agent (created to prevent out-of-sync issues)
- **Triggered by**: Phase 7 Step 3 completion revealed need for systematic context management before future steps
