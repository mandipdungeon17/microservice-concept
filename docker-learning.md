# Docker & Docker Compose — Complete Reference

> From fundamentals to production patterns.
> Covers: images, containers, Dockerfile, networking, volumes, environment variables, logging, Docker Compose, multi-stage builds.

---

## 1. Core Concepts: Image vs Container

### Image

A **read-only template** containing application code, runtime, libraries, and filesystem. Built from a `Dockerfile`. Stored in registries (Docker Hub, GitHub Container Registry, AWS ECR).

Think of it as a **class** in OOP — it defines the blueprint but doesn't run anything.

```
Image = OS base layer + application layer + configuration layer (all read-only)
```

### Container

A **running instance** of an image. Has its own writable filesystem layer (copy-on-write), network interface, and process namespace.

Think of it as an **object** — instantiated from a class, with its own state.

```
Container = Image (read-only) + Writable layer (container state) + Process isolation
```

### Key relationship:

```
Dockerfile  →(docker build)→  Image  →(docker run)→  Container
                                ↑                        ↑
                          Stored in registry       Running process
                          (reusable, shareable)    (ephemeral, disposable)
```

**One image, many containers.** You can run 10 containers from the same `redis:7` image — each has its own data, port, and lifecycle.

---

## 2. Dockerfile — Building Images

A `Dockerfile` is a text file with instructions that Docker executes sequentially to build an image. Each instruction creates a **layer** (cached for rebuild speed).

### Instruction Reference

| Instruction   | Purpose                                               | Example                                                         |
| ------------- | ----------------------------------------------------- | --------------------------------------------------------------- |
| `FROM`        | Base image (must be first)                            | `FROM eclipse-temurin:21-jre-alpine`                            |
| `WORKDIR`     | Set working directory (mkdir + cd)                    | `WORKDIR /app`                                                  |
| `COPY`        | Copy files from host → image                          | `COPY build/libs/*.jar app.jar`                                 |
| `RUN`         | Execute command during build (creates layer)          | `RUN apt-get update && apt-get install -y curl`                 |
| `ENV`         | Set environment variable                              | `ENV JAVA_OPTS="-Xmx512m"`                                      |
| `EXPOSE`      | Document which port the app uses (informational only) | `EXPOSE 8080`                                                   |
| `ENTRYPOINT`  | Main process command (PID 1)                          | `ENTRYPOINT ["java", "-jar", "app.jar"]`                        |
| `CMD`         | Default arguments to ENTRYPOINT (overridable)         | `CMD ["--spring.profiles.active=docker"]`                       |
| `ARG`         | Build-time variable (not available at runtime)        | `ARG JAR_FILE=app.jar`                                          |
| `LABEL`       | Metadata key-value pair                               | `LABEL maintainer="team@example.com"`                           |
| `HEALTHCHECK` | Container health probe                                | `HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health` |

### ENTRYPOINT vs CMD

```dockerfile
# ENTRYPOINT = the executable (rarely overridden)
# CMD = default arguments (easily overridden at docker run)
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["--server.port=8080"]

# At runtime: java -jar app.jar --server.port=8080
# Override CMD: docker run myimage --server.port=9090
# Override ENTRYPOINT: docker run --entrypoint /bin/sh myimage
```

### Layer Caching

Each instruction creates a cached layer. Docker rebuilds from the **first changed layer** downward. Order matters:

```dockerfile
# BAD — any code change invalidates dependency layer
COPY . /app
RUN ./gradlew build

# GOOD — dependencies cached separately from code
COPY build.gradle settings.gradle /app/
RUN ./gradlew dependencies        # ← cached if build.gradle unchanged
COPY src /app/src
RUN ./gradlew build               # ← only this reruns on code change
```

### Multi-Stage Build (production pattern)

Problem: build tools (JDK, Gradle, Node) are large (800MB+). Runtime only needs JRE (~200MB).
Solution: build in one stage, copy artifact to a minimal runtime stage.

```dockerfile
# ════════════════════════════════════════════════════════════
# Stage 1: BUILD (large image with JDK + Gradle)
# ════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Copy Gradle wrapper + build files (layer cached if unchanged)
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./
COPY commons/build.gradle commons/

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code + build
COPY src/ src/
RUN ./gradlew bootJar --no-daemon

# ════════════════════════════════════════════════════════════
# Stage 2: RUNTIME (minimal image with JRE only)
# ════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy ONLY the built JAR from Stage 1
COPY --from=builder /build/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Result:** Final image ~200MB (JRE + JAR) instead of ~800MB (full JDK + Gradle cache + source).

Stage 1 (`builder`) is discarded — only Stage 2 becomes the final image.

---

## 3. Building and Running

### Build an image

```bash
# Build from Dockerfile in current directory
docker build -t my-service:1.0 .

# Build with build argument
docker build --build-arg JAR_FILE=service.jar -t my-service:1.0 .

# Build with specific Dockerfile
docker build -f Dockerfile.prod -t my-service:prod .
```

The `-t` flag tags the image with a name:version. Without it, Docker assigns a random hash.

### Run a container

```bash
# Basic run (foreground)
docker run my-service:1.0

# Detached (background) + port mapping + name
docker run -d --name order-service -p 8088:8080 my-service:1.0

# With environment variables
docker run -d --name order-service \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/equitycart_order \
  -e SPRING_PROFILES_ACTIVE=docker \
  -p 8088:8080 \
  my-service:1.0

# With volume mount
docker run -d --name mongodb -v mongo-data:/data/db -p 27017:27017 mongo:7
```

### Container lifecycle

```bash
docker ps                    # List running containers
docker ps -a                 # List all (including stopped)
docker logs order-service    # View stdout/stderr
docker logs -f order-service # Follow logs (tail -f)
docker exec -it redis redis-cli  # Execute command inside running container
docker stop order-service    # Graceful shutdown (SIGTERM → 10s → SIGKILL)
docker start order-service   # Start stopped container (state preserved)
docker rm order-service      # Remove container (must be stopped first)
docker rm -f order-service   # Force remove (stop + remove)
```

---

## 4. Networking

### Network Types

| Type               | Use Case                  | Container-to-Container     | Host Access              |
| ------------------ | ------------------------- | -------------------------- | ------------------------ |
| `bridge` (default) | Single-host isolation     | By container name/IP       | Via port mapping (`-p`)  |
| `host`             | Maximum performance       | Shares host network stack  | Direct (no port mapping) |
| `none`             | Complete isolation        | No networking              | None                     |
| `overlay`          | Multi-host (Docker Swarm) | Cross-host by service name | Via ingress routing      |

### Bridge Network (Default)

When you run `docker run`, the container joins the default `bridge` network. Containers on the default bridge can reach each other **only by IP** (not by name — DNS disabled on default bridge).

```bash
# Create a custom bridge network (enables DNS by name):
docker network create equitycart-net

# Run containers on the custom network:
docker run -d --name kafka --network equitycart-net apache/kafka:latest
docker run -d --name debezium --network equitycart-net debezium/connect:2.5

# Now debezium can reach kafka by name: kafka:29092
```

### Docker Compose Default Network

Docker Compose automatically creates a custom bridge network for all services in the file. This is why services can reach each other by service name without any manual network configuration.

```yaml
services:
  kafka: # ← accessible as "kafka" from other services
    image: apache/kafka:latest
  debezium: # ← accessible as "debezium" from other services
    image: debezium/connect:2.5
    environment:
      BOOTSTRAP_SERVERS: kafka:29092 # ← uses service name as hostname
```

The network is named `<directory>_default` (e.g., `docker_default` if your Compose file is in `docker/`).

### Container → Host Communication

Containers need to reach services running on the host machine (e.g., your local PostgreSQL).

| Platform                 | How to reach host                      | Notes                                                                            |
| ------------------------ | -------------------------------------- | -------------------------------------------------------------------------------- |
| Windows (Docker Desktop) | `host.docker.internal`                 | Built-in, always available                                                       |
| macOS (Docker Desktop)   | `host.docker.internal`                 | Built-in, always available                                                       |
| Linux                    | `host.docker.internal` (Docker 20.10+) | Requires `--add-host=host.docker.internal:host-gateway` or Compose `extra_hosts` |

```yaml
# Debezium connector config (register-connector.json):
{ "database.hostname": "host.docker.internal", "database.port": "5432" }
```

### Host → Container Communication

From the host, reach containers via `localhost:MAPPED_PORT` (because of `-p` port mapping).

```
Host app (Spring Boot on localhost:8084)
  → localhost:9092 (Kafka — mapped from container port 9092)
  → localhost:6379 (Redis — mapped from container port 6379)
  → localhost:27017 (MongoDB — mapped from container port 27017)
```

### Port Mapping Explained

```yaml
ports:
  - "8761:8080"
#    ^^^^  ^^^^
#    HOST  CONTAINER
#
#  "I want to access container's port 8080 via my host's port 8761"
#  Host:8761 → Container:8080
```

If the app inside the container listens on 8080, and you map `8761:8080`, then from your host browser you access `http://localhost:8761`.

### Dual-Listener Pattern (Kafka)

Kafka needs to be accessible from BOTH:

1. Host applications (Spring Boot on localhost) → `localhost:9092`
2. Other containers (Debezium in same Compose network) → `kafka:29092`

```yaml
environment:
  # What Kafka binds to (inside container — all interfaces):
  KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,DOCKER://:29092

  # What Kafka ADVERTISES to clients (what clients use to connect back):
  KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,DOCKER://kafka:29092
  #                                      ^^^^^^^^^^^^^^^^^       ^^^^^^^^^^
  #                                      For host apps           For containers
```

**Why two listeners?**

- Host app connects to `localhost:9092` → Kafka says "I'm at `localhost:9092`" → works (port mapped)
- Container connects to `kafka:29092` → Kafka says "I'm at `kafka:29092`" → works (Compose DNS)

If Kafka only advertised `localhost:9092`, containers would try to connect to their OWN localhost, not the Kafka container.

---

## 5. Volumes — Persistent Storage

### The Problem

Containers have a **writable layer** that stores runtime changes (files created, DB data written). This layer is **ephemeral** — destroyed when the container is removed (`docker rm` or `docker compose down`).

For stateful services (databases, message brokers), you need data to survive container restarts and removal.

### Volume Types

| Type         | Syntax                         | Managed By            | Best For                               |
| ------------ | ------------------------------ | --------------------- | -------------------------------------- |
| Named volume | `my-vol:/data/db`              | Docker                | Databases, persistent state            |
| Bind mount   | `./local/path:/container/path` | You (host filesystem) | Config files, source code, development |
| tmpfs        | `tmpfs: /tmp`                  | Kernel (RAM)          | Secrets, ephemeral scratch space       |

### Named Volumes (recommended for databases)

```yaml
services:
  mongodb:
    image: mongo:7
    volumes:
      - mongo-data:/data/db # Named volume → container's data directory

  redis:
    image: redis:7
    volumes:
      - redis-data:/data # Redis dumps RDB here

  kafka:
    image: apache/kafka:latest
    volumes:
      - kafka-data:/var/kafka/data # Topic log segments stored here

# MUST declare named volumes at top level:
volumes:
  mongo-data: # Docker creates and manages this
  redis-data:
  kafka-data:
```

**Where do named volumes live on disk?**

- Linux: `/var/lib/docker/volumes/<name>/_data`
- Windows (Docker Desktop): Inside the WSL2 VM at `\\wsl$\docker-desktop-data\data\docker\volumes\`

You don't manage the location — Docker handles it. That's the point.

### Bind Mounts (for config files / development)

```yaml
services:
  debezium:
    image: debezium/connect:2.5
    volumes:
      - ./debezium/register-connector.json:/kafka/config/connector.json:ro
      #  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
      #  Host path (relative to compose file)  Container path (:ro = read-only)
```

### Volume Lifecycle Commands

```bash
# List all volumes
docker volume ls

# Inspect volume (see mountpoint, creation date)
docker volume inspect docker_mongo-data

# Remove unused volumes (not attached to any container)
docker volume prune

# Remove specific volume
docker volume rm docker_mongo-data

# docker compose down: keeps volumes (data preserved)
docker compose down

# docker compose down -v: removes volumes (DATA DELETED)
docker compose down -v
```

### Which services need volumes?

| Service  | Needs Volume?  | Why                                                               |
| -------- | -------------- | ----------------------------------------------------------------- |
| MongoDB  | Yes            | Database files                                                    |
| Redis    | Yes (optional) | RDB persistence (or accept data loss on restart)                  |
| Kafka    | Yes            | Topic log segments, consumer offsets                              |
| Debezium | No             | State stored in Kafka topics (CONFIG/OFFSET/STATUS_STORAGE_TOPIC) |
| MailHog  | No             | Trapped emails are ephemeral (dev tool)                           |

---

## 6. Environment Variables

### In Dockerfile (build-time defaults)

```dockerfile
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV SPRING_PROFILES_ACTIVE=docker
```

These become defaults inside the image. Can be overridden at runtime.

### In docker run

```bash
docker run -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/db my-image
```

### In Docker Compose

Two syntaxes (both valid, same result):

```yaml
# List syntax (shell-style):
environment:
  - KAFKA_NODE_ID=1
  - BOOTSTRAP_SERVERS=kafka:29092

# Map syntax (YAML native — recommended, easier to read):
environment:
  KAFKA_NODE_ID: 1
  BOOTSTRAP_SERVERS: kafka:29092
```

### Environment file (.env)

For sensitive values or values shared across services:

```bash
# .env file (same directory as docker-compose.yml):
POSTGRES_PASSWORD=secret123
KAFKA_CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk
```

```yaml
# docker-compose.yml:
services:
  kafka:
    environment:
      CLUSTER_ID: ${KAFKA_CLUSTER_ID}
```

Or load entire file:

```yaml
services:
  my-service:
    env_file: .env
```

### Spring Boot Environment Variable Mapping

Spring Boot's relaxed binding maps environment variables to properties:

```
YAML property:                    Environment variable:
spring.datasource.url        →    SPRING_DATASOURCE_URL
spring.jpa.hibernate.ddl-auto →   SPRING_JPA_HIBERNATE_DDL_AUTO
eureka.client.service-url.default-zone → EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
server.port                  →    SERVER_PORT
```

Rules: dots → underscores, hyphens → removed, all uppercase.

This is how you override application.yml values when running in Docker without changing any code:

```yaml
services:
  order-service:
    image: order-service:latest
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://host.docker.internal:5432/equitycart_order
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE: http://discovery:8761/eureka/
      SERVER_PORT: 8080
```

---

## 7. Logging

### Default Logging

Docker captures everything written to `stdout` and `stderr` by the container's PID 1 process. View with:

```bash
docker logs <container>           # All logs
docker logs -f <container>        # Follow (live tail)
docker logs --tail 100 <container> # Last 100 lines
docker logs --since 5m <container> # Last 5 minutes
```

### Logging Drivers

Control WHERE Docker sends logs:

| Driver                | Destination             | Use Case                                               |
| --------------------- | ----------------------- | ------------------------------------------------------ |
| `json-file` (default) | Local JSON files        | Development, small deployments                         |
| `local`               | Optimized local storage | Better than json-file (compressed, rotated)            |
| `syslog`              | Syslog daemon           | Linux system logging                                   |
| `fluentd`             | Fluentd collector       | Centralized logging (ELK stack)                        |
| `awslogs`             | AWS CloudWatch          | AWS deployments                                        |
| `gcplogs`             | Google Cloud Logging    | GCP deployments                                        |
| `none`                | Nowhere (disabled)      | High-throughput services where logs handled internally |

```yaml
services:
  kafka:
    image: apache/kafka:latest
    logging:
      driver: json-file
      options:
        max-size: "10m" # Rotate log file at 10MB
        max-file: "3" # Keep 3 rotated files (30MB total max)
```

### Log Rotation (important for long-running containers)

Without rotation, `docker logs` files grow unbounded and can fill disk:

```yaml
# Apply to all services:
services:
  kafka:
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

Or set globally in Docker daemon config (`/etc/docker/daemon.json` or Docker Desktop settings):

```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
```

---

## 8. Docker Compose — Orchestrating Multiple Containers

### What Docker Compose Does

Replaces multiple `docker run` commands with a single declarative YAML file. One command (`docker compose up`) creates the network, pulls images, creates containers, and starts everything in dependency order.

### File Structure

```yaml
# No 'version' field needed (modern Compose ignores it)
services:
  service-name:
    image: image:tag
    container_name: explicit-name # Optional (default: <dir>-<service>-<N>)
    ports:
      - "host:container"
    environment:
      KEY: value
    volumes:
      - named-vol:/container/path
    depends_on:
      - other-service
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  named-vol:

networks:
  custom-net: # Optional (default network created automatically)
```

### depends_on — Startup Ordering

```yaml
services:
  kafka:
    image: apache/kafka:latest

  debezium:
    depends_on:
      - kafka              # Simple: waits for kafka container to START
    # OR with health condition:
    depends_on:
      kafka:
        condition: service_healthy  # Waits for kafka's healthcheck to pass
```

**Important:** `depends_on` waits for the container to start, NOT for the application inside to be ready. Kafka container starts in 1 second, but Kafka broker takes 5-10 seconds to accept connections. For robust ordering, use `condition: service_healthy` with a `healthcheck` on the dependency.

### Healthchecks

```yaml
services:
  kafka:
    image: apache/kafka:latest
    healthcheck:
      test:
        [
          "CMD",
          "/opt/kafka/bin/kafka-broker-api-versions.sh",
          "--bootstrap-server",
          "localhost:9092",
        ]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s # Grace period before first check
```

### Docker Compose Commands

```bash
# Start all services (detached)
docker compose up -d

# Start specific service (+ its dependencies)
docker compose up -d kafka debezium

# Stop all (containers preserved, can restart)
docker compose stop

# Stop + remove containers + default network (volumes KEPT)
docker compose down

# Stop + remove containers + volumes (DATA DELETED)
docker compose down -v

# Rebuild images (if using build: context)
docker compose build

# View running services
docker compose ps

# View logs (all services, follow mode)
docker compose logs -f

# View logs (specific service)
docker compose logs -f kafka

# Execute command in running service
docker compose exec redis redis-cli PING

# Scale a service (run multiple instances)
docker compose up -d --scale worker=3
```

### Multiple Compose Files

You can split infrastructure and application services:

```bash
# Start only infrastructure
docker compose -f docker-pets.yml up -d

# Start services (separate file)
docker compose -f docker-compose-services.yml up -d

# Start both together (merged)
docker compose -f docker-pets.yml -f docker-compose-services.yml up -d
```

---

## 9. Container Naming in Compose

### Default naming convention:

```
<project-name>-<service-name>-<instance-number>
```

Where project name = directory name containing the Compose file.

Example: file at `equitycart/docker/docker-pets.yml`, run with `docker compose -f docker-pets.yml up`:

- `docker-kafka-1`
- `docker-redis-1`
- `docker-debezium-1`

### Explicit naming:

```yaml
services:
  kafka:
    container_name: kafka # Overrides default naming
```

With `container_name: kafka`, commands use that name directly:

```bash
docker exec -it kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
```

Without it, you'd need:

```bash
docker exec -it docker-kafka-1 /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
```

**Trade-off:** `container_name` prevents scaling (`docker compose up --scale kafka=3` fails because names must be unique).

---

## 10. Image Tagging Strategy

### Tags explained

```
registry/repository:tag
docker.io/library/redis:7.2.4-alpine
^^^^^^^^  ^^^^^^^ ^^^^^  ^^^^^^^^^^^
registry  org     name   tag (version + variant)
```

### Common tag patterns:

| Tag                  | Meaning                                        | Stability                    |
| -------------------- | ---------------------------------------------- | ---------------------------- |
| `redis:latest`       | Most recent build (moves constantly)           | Unstable — breaks silently   |
| `redis:7`            | Latest patch of major version 7                | Semi-stable                  |
| `redis:7.2`          | Latest patch of 7.2.x                          | More stable                  |
| `redis:7.2.4`        | Exact version                                  | Fully reproducible           |
| `redis:7.2.4-alpine` | Exact version + Alpine Linux variant (smaller) | Fully reproducible + minimal |

**Best practice:** Pin at least major.minor for infrastructure images. Use `latest` only in development exploration.

---

## 11. host.docker.internal Deep Dive

### What it is

A special DNS name that resolves to the host machine's internal IP from inside a container. Provided automatically by Docker Desktop on Windows and macOS.

### Why you need it

Your PostgreSQL runs on the host (localhost:5432). Containers can't use `localhost` to reach the host — inside a container, `localhost` means the container itself. `host.docker.internal` bridges this gap.

### Where it's used in EquityCart

1. **Debezium connector config** — reads PostgreSQL WAL:

   ```json
   "database.hostname": "host.docker.internal"
   ```

2. **Spring Boot services (if containerized)** — JDBC connection:

   ```yaml
   SPRING_DATASOURCE_URL: jdbc:postgresql://host.docker.internal:5432/equitycart_order
   ```

3. **NOT needed for container-to-container** — use service names:
   ```yaml
   BOOTSTRAP_SERVERS: kafka:29092 # NOT host.docker.internal:29092
   ```

---

## 12. Docker vs Docker Compose vs Docker Swarm vs Kubernetes

| Tool             | Scope                               | Purpose                                                 |
| ---------------- | ----------------------------------- | ------------------------------------------------------- |
| Docker Engine    | Single container                    | Build, run, manage individual containers                |
| Docker Compose   | Multiple containers, single host    | Define + run multi-container apps (development/testing) |
| Docker Swarm     | Multiple containers, multiple hosts | Native Docker orchestration (built-in, simpler)         |
| Kubernetes (K8s) | Multiple containers, multiple hosts | Industry-standard orchestration (complex, powerful)     |

**Your current setup:** Docker Compose for infrastructure, host-run Spring Boot apps. This is the standard development workflow. Production would add Kubernetes (Phase 10 territory).

---

## 13. Useful Docker Commands Reference

### Images

```bash
docker images                    # List local images
docker pull redis:7              # Download image from registry
docker rmi redis:7               # Remove local image
docker image prune               # Remove dangling (untagged) images
docker image prune -a            # Remove all unused images
```

### Containers

```bash
docker ps                        # Running containers
docker ps -a                     # All containers (including stopped)
docker inspect kafka             # Full JSON details (network, mounts, config)
docker stats                     # Live CPU/memory/network per container
docker top kafka                 # Processes inside container
```

### Networking

```bash
docker network ls                # List networks
docker network inspect bridge    # Details of a network
docker network create my-net     # Create custom bridge
docker network connect my-net container-name  # Attach running container
```

### Volumes

```bash
docker volume ls                 # List volumes
docker volume inspect vol-name   # Details (mountpoint)
docker volume rm vol-name        # Delete volume
docker volume prune              # Remove all unused volumes
```

### Cleanup (reclaim disk space)

```bash
docker system df                 # Show Docker disk usage
docker system prune              # Remove stopped containers + unused networks + dangling images
docker system prune -a --volumes # Nuclear option: remove EVERYTHING unused
```

---

## 14. Common Pitfalls

### 1. Port conflict

```
Error: bind: address already in use
```

Another process (or container) already uses that port. Check with:

```bash
# Windows:
netstat -ano | findstr :8083

# Kill the process or change port mapping
```

### 2. Container can't reach host service

- **Wrong:** `localhost:5432` (resolves to container's own loopback)
- **Right:** `host.docker.internal:5432` (resolves to host)

### 3. Container can't reach another container

- **Wrong:** `localhost:9092` (only works from the host)
- **Right:** `kafka:29092` (Compose DNS resolves service name)

### 4. Data lost after `docker compose down -v`

The `-v` flag removes volumes. Without `-v`, `docker compose down` preserves volumes.

### 5. Stale image after code change

Docker caches layers. After changing code, rebuild:

```bash
docker compose build --no-cache my-service
docker compose up -d my-service
```

### 6. `latest` tag doesn't mean "newest"

`latest` is just a tag name — it's whatever the image publisher tagged as `latest`. If you already pulled `redis:latest` yesterday, `docker compose up` won't pull a newer version unless you explicitly run `docker compose pull`.

---

## 15. EquityCart Docker Architecture — Fully Containerized (Phase 7)

### Previous State (Phase 6): Hybrid Mode

Infrastructure in Docker, Spring Boot services on the host. Services connected to infrastructure via `localhost:MAPPED_PORT`.

### Current State (Phase 7): Everything in Docker

```
┌─────────────── HOST MACHINE (Windows 11 + Docker Desktop) ────────────────────┐
│                                                                                 │
│  Browser: http://localhost:8080/api/...  (only entry point)                    │
│                                          │                                     │
│                                          ▼ Port mapping 8080:8080              │
│  ┌─── Docker Network: docker_default (bridge) ────────────────────────────┐    │
│  │                                                                         │    │
│  │  ┌── Infrastructure (docker-pets.yml) ──────────────────────────────┐  │    │
│  │  │  postgres    (5432)  ← 7 databases via init-db.sh               │  │    │
│  │  │  kafka       (9092, 29092)  ← KRaft mode, dual-listener          │  │    │
│  │  │  redis       (6379)                                               │  │    │
│  │  │  mongodb     (27017)                                              │  │    │
│  │  │  debezium    (8083)  ← Kafka Connect + CDC                       │  │    │
│  │  │  mailhog     (1025 SMTP, 8025 Web UI)                            │  │    │
│  │  └──────────────────────────────────────────────────────────────────┘  │    │
│  │                                                                         │    │
│  │  ┌── Application Services (docker-compose-services.yml) ────────────┐  │    │
│  │  │  discovery        (8761)  ← Eureka Server (service registry)     │  │    │
│  │  │  config-server    (8888)  ← Spring Cloud Config (Git-backed)     │  │    │
│  │  │  api-gateway      (8080)  ← Entry point, routes to lb://SERVICE  │  │    │
│  │  │  user-service     (8081)  ← Auth, JWT, user management           │  │    │
│  │  │  order-service    (8088)  ← Cart, orders, outbox, stock lock     │  │    │
│  │  │  portfolio-service(8084)  ← Holdings, trade, saga, vesting       │  │    │
│  │  │  product-service  (8089)  ← Catalog, batch import, Redis cache   │  │    │
│  │  │  market-data-service(8085)← Prices, SSE, MongoDB, Resilience4j  │  │    │
│  │  │  ledger-service   (8086)  ← Double-entry bookkeeping             │  │    │
│  │  │  notification-service(8087)← Kafka consumer, strategy dispatch   │  │    │
│  │  └──────────────────────────────────────────────────────────────────┘  │    │
│  │                                                                         │    │
│  │  Inter-container DNS:                                                   │    │
│  │    kafka:29092  redis:6379  postgres:5432  mongodb:27017                │    │
│  │    discovery:8761  config-server:8888                                   │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│                                                                                 │
│  Host → Container: localhost:PORT (port-mapped services only)                  │
│  Container → Container: service-name:PORT (Docker DNS, no port mapping needed) │
│  Container → Host: host.docker.internal (not needed in fully-containerized)    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 16. Building Docker Images for Spring Boot Services

### The Pipeline: Source Code → Docker Image

```
┌──────────────────────────────────────────────────────────────────┐
│  Source Code (*.java)                                             │
│       │                                                          │
│       ▼ ./gradlew :module:bootJar                                │
│  FAT JAR (module/build/libs/module-service-exec.jar)             │
│       │    Contains: compiled .class files + all dependencies     │
│       │    + embedded Tomcat + application.yml + Spring Boot loader│
│       │                                                          │
│       ▼ docker build -t service-name:latest .                    │
│  Docker Image (layered filesystem)                               │
│       Layer 1: eclipse-temurin:21-jre-alpine (base OS + JRE)     │
│       Layer 2: WORKDIR /app (directory creation)                  │
│       Layer 3: COPY app.jar (your fat JAR)                        │
│       Layer 4: ENTRYPOINT ["java", "-jar", "app.jar"]             │
│                                                                  │
│       ▼ docker compose up -d                                      │
│  Running Container (Linux process with isolated namespace)        │
└──────────────────────────────────────────────────────────────────┘
```

### Dockerfile for EquityCart Services

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*-exec.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Why `*-exec.jar`? When a module has both `jar { enabled = true }` (library artifact for other modules) and `bootJar` (executable), Gradle produces two JARs. The `bootJar { archiveClassifier.set('exec') }` flag names the executable `*-exec.jar` to avoid conflicts.

### Build Script (build-images.sh)

```bash
#!/bin/bash
set -e
cd "$(dirname "$0")/.."   # equitycart root

MODULES=(
  "discovery-server:discovery-server"
  "config-server:config-server"
  "api-gateway:api-gateway"
  "user-service:user"
  "order-service:order"
  "portfolio-service:portfolio"
  "product-service:product"
  "market-data-service:market-data"
  "ledger-service:ledger"
  "notification-service:notification"
)

echo "==> Building all JARs..."
./gradlew bootJar

for entry in "${MODULES[@]}"; do
  IMAGE_NAME="${entry%%:*}"
  MODULE_DIR="${entry##*:}"
  echo "==> Building image: $IMAGE_NAME from $MODULE_DIR/"
  docker build -t "$IMAGE_NAME:latest" "$MODULE_DIR/"
done

echo "==> All images built."
docker images | grep -E "discovery|config|gateway|user|order|portfolio|product|market|ledger|notification"
```

### Layer Caching — When Rebuilds Happen

```
Layer 1: FROM eclipse-temurin:21-jre-alpine   ← CACHED (never changes)
Layer 2: WORKDIR /app                          ← CACHED (never changes)
Layer 3: COPY build/libs/*-exec.jar app.jar    ← INVALIDATED if JAR changed
Layer 4: ENTRYPOINT [...]                      ← REBUILT (after invalidated layer)
```

**Key insight:** Any change to your Java code → new JAR → Layer 3 invalidated → Layer 4 rebuilt. The base image (Layer 1-2) is never re-downloaded. This is why rebuilds are fast (seconds) after the first build.

For production, multi-stage builds would add a build stage (JDK + Gradle + compile) before the runtime stage — but for learning, pre-built JARs are simpler.

---

## 17. Docker Networking — Deep Dive (Layer by Layer)

### Mental Model: Three Separate Worlds

```
┌─────────────────────────────────────────────────────────┐
│ WORLD 1: Your Physical Machine (Host)                   │
│   IP: 192.168.x.x (your LAN IP)                        │
│   Loopback: 127.0.0.1 (localhost)                       │
│   Runs: Docker Desktop, your browser, your IDE          │
│                                                         │
│   Ports you can access: localhost:8080, localhost:8761   │
│   (only if port-mapped from a container)                │
└─────────────────────┬───────────────────────────────────┘
                      │
                      │ Docker Desktop creates a virtual network
                      │ (Linux VM on Windows/macOS via WSL2/HyperKit)
                      ▼
┌─────────────────────────────────────────────────────────┐
│ WORLD 2: Docker Bridge Network (docker_default)         │
│   Subnet: 172.18.0.0/16 (auto-assigned by Docker)      │
│   Gateway: 172.18.0.1 (Docker host bridge interface)    │
│                                                         │
│   Built-in DNS server resolves container/service names: │
│     "kafka"    → 172.18.0.3                             │
│     "postgres" → 172.18.0.2                             │
│     "discovery" → 172.18.0.10                           │
│                                                         │
│   Each container gets its own IP on this subnet.        │
│   Containers can reach each other by NAME (DNS).        │
└─────────────────────┬───────────────────────────────────┘
                      │
                      │ Each container is an isolated Linux process
                      ▼
┌─────────────────────────────────────────────────────────┐
│ WORLD 3: Inside a Container                             │
│   IP: 172.18.0.X (assigned by Docker)                   │
│   Loopback: 127.0.0.1 (container's OWN localhost)       │
│                                                         │
│   "localhost" = THIS container only (not the host!)     │
│   "kafka" = Docker DNS → 172.18.0.3 (another container)│
│   "host.docker.internal" = the host machine (World 1)   │
└─────────────────────────────────────────────────────────┘
```

### How a Request Flows: Browser → API Gateway → Service → Database

```
Step 1: You type http://localhost:8080/api/order in your browser
        │
        │ Browser sends to localhost:8080
        │ "localhost" on your host = 127.0.0.1 (World 1)
        │
        ▼
Step 2: Docker's port mapping catches it
        │
        │ Docker sees: "port 8080 on host is mapped to port 8080
        │ on container docker-api-gateway-1"
        │ (defined by `ports: - 8080:8080` in docker-compose)
        │
        │ Packet forwarded: 127.0.0.1:8080 → 172.18.0.12:8080
        │
        ▼
Step 3: API Gateway receives the request
        │
        │ Gateway reads route config: Path=/api/order/** → lb://ORDER-SERVICE
        │ "lb://" means: ask Eureka for ORDER-SERVICE instances
        │
        │ Gateway queries Eureka (http://discovery:8761/eureka/)
        │ Docker DNS resolves "discovery" → 172.18.0.10
        │ Eureka responds: ORDER-SERVICE is at 172.18.0.15:8088
        │
        ▼
Step 4: Gateway forwards request to Order Service
        │
        │ Direct container-to-container: 172.18.0.12 → 172.18.0.15:8088
        │ No port mapping needed! Both on same Docker network.
        │
        ▼
Step 5: Order Service connects to PostgreSQL
        │
        │ JDBC URL: jdbc:postgresql://postgres:5432/equitycart_order
        │ Docker DNS resolves "postgres" → 172.18.0.2
        │ Connection: 172.18.0.15 → 172.18.0.2:5432
        │
        ▼
Step 6: Response flows back: DB → Order → Gateway → Browser
```

### Why `localhost` Doesn't Work Between Containers

```
Inside order-service container:
  - localhost:5432 → means "port 5432 on THIS container"
  - But PostgreSQL isn't running inside order-service!
  - PostgreSQL is in a DIFFERENT container with IP 172.18.0.2

  - postgres:5432 → Docker DNS resolves → 172.18.0.2:5432 ✓
  - localhost:5432 → 127.0.0.1:5432 (order-service's own loopback) ✗
```

### Eureka Registration IP Problem

When a service registers with Eureka, it tells Eureka "my address is X". Eureka stores this. When the gateway asks "where is ORDER-SERVICE?", Eureka returns that stored address.

**Problem with `prefer-ip-address: true`:**

```
Service registers: "I am at 172.18.0.15:8088"
Eureka stores: 172.18.0.15:8088
Gateway routes to: 172.18.0.15:8088 ← works within Docker network

But your BROWSER on the host clicks Eureka's link:
  http://172.18.0.15:8088/actuator/info ← FAILS!
  Because 172.18.0.0/16 is NOT routable from your host machine.
  Your host's network stack doesn't know how to reach that subnet.
```

**Solution:** From the browser, use `localhost:8088` (port-mapped). The 172.x IPs are internal-only — they're correct for container-to-container communication but invisible from the host.

### Port Mapping: The Bridge Between Worlds

```yaml
ports:
  - "8088:8088"
  #   ^^^^  ^^^^
  #   HOST  CONTAINER
  #
  # Creates a NAT rule:
  #   Host 0.0.0.0:8088 → Container 172.18.0.15:8088
  #
  # Without this mapping, the container is ONLY reachable
  # from other containers on the same Docker network.
```

**Which services actually need port mapping?**

| Service              | Port Mapped?    | Why                             |
| -------------------- | --------------- | ------------------------------- |
| api-gateway          | Yes (8080:8080) | Browser entry point             |
| discovery            | Yes (8761:8761) | Eureka dashboard in browser     |
| config-server        | Yes (8888:8888) | Debug: view configs in browser  |
| postgres             | Yes (5432:5432) | Host tools (pgAdmin, DBeaver)   |
| kafka                | Yes (9092:9092) | Host apps connecting during dev |
| mailhog              | Yes (8025:8025) | Email UI in browser             |
| application services | Optional        | Only for direct debugging       |

In production, only the gateway would be exposed. All other services communicate internally.

---

## 18. Environment Variables — The Resolution Chain

### The Full Chain: Docker Compose → Container → Spring Boot → Config Server → Application

```
┌─────────── docker-compose-services.yml ──────────────────┐
│  order-service:                                          │
│    environment:                                          │
│      - SERVER_PORT=8088                                  │
│      - CONFIG_SERVER_URL=http://config-server:8888       │
│      - EUREKA_URL=http://discovery:8761/eureka/          │
│      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres  │
│        :5432/equitycart_order                            │
│      - SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092        │
└──────────────────────────┬───────────────────────────────┘
                           │
                           │ Docker injects as OS environment vars
                           ▼
┌─────────── Inside Container (Linux process env) ─────────┐
│  $ env | grep SPRING                                     │
│  SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/  │
│  SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092              │
│  CONFIG_SERVER_URL=http://config-server:8888             │
│  EUREKA_URL=http://discovery:8761/eureka/                │
└──────────────────────────┬───────────────────────────────┘
                           │
                           │ Spring Boot starts
                           ▼
┌─────────── Spring Boot Property Resolution ──────────────┐
│  1. OS environment variables (HIGHEST priority)          │
│  2. Config Server properties (fetched via import)        │
│  3. application.yml embedded in JAR (LOWEST priority)    │
│                                                          │
│  Priority: ENV VAR > Config Server > embedded YAML       │
└──────────────────────────┬───────────────────────────────┘
                           │
                           │ spring.config.import=configserver:
                           │   ${CONFIG_SERVER_URL:http://localhost:8888}
                           │ Docker sets CONFIG_SERVER_URL=http://config-server:8888
                           │ → fetches from http://config-server:8888
                           ▼
┌─────────── Config Server Responds With ──────────────────┐
│  (from equitycart-config/application.yml):               │
│    spring.kafka.bootstrap-servers:                        │
│      ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}     │
│                                                          │
│  This is a PLACEHOLDER — not a final value!              │
│  The CLIENT resolves it using its own env vars.          │
│                                                          │
│  Client env: SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092  │
│  → Resolved value: kafka:29092 ✓                         │
└──────────────────────────────────────────────────────────┘
```

### The Placeholder Pattern: `${ENV_VAR:default}`

This is the key pattern that makes configs work in BOTH local development AND Docker:

```yaml
# In equitycart-config/application.yml (served by config-server):
eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
```

**How it resolves in two contexts:**

| Context               | EUREKA_URL env var              | Resolved value                                 |
| --------------------- | ------------------------------- | ---------------------------------------------- |
| Local dev (no Docker) | Not set                         | `http://localhost:8761/eureka/` (default)      |
| Docker Compose        | `http://discovery:8761/eureka/` | `http://discovery:8761/eureka/` (env var wins) |

**Key principle:** The config-server does NOT resolve the placeholder. It sends `${EUREKA_URL:http://localhost:8761/eureka/}` as-is to the client. The CLIENT resolves it against its own environment variables. This is why Docker Compose sets the env var on the client service, not on the config-server.

### Spring Boot Relaxed Binding (env var → property mapping)

```
YAML property:                              Environment variable:
─────────────────────────────────────────   ──────────────────────────────────
spring.datasource.url                   →   SPRING_DATASOURCE_URL
eureka.client.service-url.default-zone  →   EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
spring.kafka.bootstrap-servers          →   SPRING_KAFKA_BOOTSTRAP_SERVERS
server.port                             →   SERVER_PORT
spring.data.redis.host                  →   SPRING_DATA_REDIS_HOST
spring.data.mongodb.uri                 →   SPRING_DATA_MONGODB_URI

Rules:
  1. Dots (.) → underscores (_)
  2. Hyphens (-) → removed
  3. All UPPERCASE
  4. Special: camelCase → CAMELCASE (no separator added)
```

### Custom Placeholders vs Relaxed Binding — Know the Difference

```yaml
# CUSTOM placeholder (your own variable name):
spring:
  config:
    import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
#                         ^^^^^^^^^^^^^^^^^^
#  This is NOT a Spring property. It's YOUR custom env var.
#  Docker must set: CONFIG_SERVER_URL=http://config-server:8888

# RELAXED BINDING (Spring Boot's automatic mapping):
# Setting SPRING_DATASOURCE_URL=jdbc:postgresql://...
# Automatically maps to: spring.datasource.url
# No placeholder needed in YAML — Spring Boot handles it.
```

**When to use each:**

- **Relaxed binding** (just set the env var): For standard Spring properties that exist in your YAML as fixed values
- **Custom placeholder** (`${MY_VAR:default}`): When you want the YAML value to be dynamic and the env var name doesn't follow Spring's convention

---

## 19. Docker Compose for Microservices — Service Dependencies & Startup Order

### Two-File Strategy

```
docker/
├── docker-pets.yml              ← Infrastructure (databases, message brokers)
├── docker-compose-services.yml  ← Application services (Spring Boot)
├── start-pets.sh                ← Start infra + wait for readiness
├── start-services.sh            ← Start discovery → config → all services
└── init-db.sh                   ← Creates multiple PostgreSQL databases
```

**Why split?** Infrastructure has different lifecycles than application code. You rebuild/restart services frequently during development but rarely touch infrastructure. Splitting means `docker compose down` on services doesn't destroy database data.

### Startup Dependency Chain

```
                postgres, kafka, redis, mongodb, mailhog
                              │
                              │ Must be READY (not just started)
                              ▼
                        discovery-server
                              │
                              │ Must be READY (Eureka accepting registrations)
                              ▼
                        config-server
                              │
                              │ Must be READY (serving configs from Git clone)
                              ▼
              ┌───────────────┼───────────────────────┐
              ▼               ▼                       ▼
        user-service    order-service          portfolio-service
                    product-service    market-data-service
                    ledger-service     notification-service
                              │
                              │ Gateway starts last (needs services registered)
                              ▼
                        api-gateway
```

### `depends_on` vs Real Readiness

```yaml
config-server:
  depends_on:
    - discovery # Only waits for container to START, not app to be READY
```

`depends_on` says "start this container after that one" — but Docker considers a container "started" the instant its process launches (1-2 seconds). A Spring Boot app needs 10-30 seconds to actually be ready. That's why the start scripts add readiness polling:

```bash
# Wait for discovery to actually respond to HTTP:
until curl -s http://localhost:8761/actuator/health | grep -q '"status":"UP"'; do
  echo "discovery not ready, retrying in 5s..."; sleep 5
done
```

### The `user: "0"` Fix (Kafka Volume Permissions)

```yaml
kafka:
  image: apache/kafka:latest
  user: "0" # Run as root inside container
  volumes:
    - kafka-data:/var/kafka/data
```

**Problem:** The `apache/kafka:latest` image runs as `appuser` (UID 1000) by default. Docker named volumes are created as root-owned directories. `appuser` can't write to a root-owned volume → `AccessDeniedException`.

**Fix:** `user: "0"` overrides the image's default user, running the Kafka process as root (UID 0) inside the container. This is acceptable for development; production would use `chown` in an entrypoint script.

### Single-Broker Kafka Settings

```yaml
environment:
  - KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1
  - KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1
  - KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1
```

**Why needed:** Kafka's internal topics (`__consumer_offsets`, `__transaction_state`) default to replication-factor=3. With only 1 broker, Kafka refuses to create them (can't replicate to brokers that don't exist) → `INVALID_REPLICATION_FACTOR` error. Setting to 1 tells Kafka "single-broker mode is OK."

---

## 20. Spring Cloud Config Server in Docker

### How Config Server Works with Git

```
┌── Config Server Container ──────────────────────────────────────────┐
│                                                                      │
│  1. On startup: git clone https://github.com/.../equitycart-config  │
│     → Stored in /tmp/config-repo-RANDOM/                            │
│     → All .yml files now available locally                          │
│                                                                      │
│  2. Client requests: GET /order-service/default                      │
│     → Reads local clone: application.yml + order-service.yml        │
│     → Merges and returns combined properties                         │
│     → Optionally tries git fetch (refresh-rate controls frequency)  │
│                                                                      │
│  3. Actuator health check: periodically tries git fetch             │
│     → If DNS fails (github.com unreachable): logs WARNING           │
│     → Configs still served from local cache — NOT broken            │
│                                                                      │
│  Key properties:                                                     │
│    clone-on-start: true    → clone immediately at boot               │
│    refresh-rate: 3600      → only re-fetch every hour                │
│    health.enabled: false   → suppress health check DNS noise         │
└──────────────────────────────────────────────────────────────────────┘
```

### Config Merge Order (what the client receives)

When order-service requests its config, the config server returns properties from:

1. `application.yml` (shared base — all services get this)
2. `order-service.yml` (service-specific overrides)

Properties in `order-service.yml` override those in `application.yml` (more specific wins).

### `spring.config.import` Is ADDITIVE

```yaml
# In service's embedded application.yml:
spring:
  config:
    import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
```

This tells Spring Boot: "Also fetch properties from config server and MERGE them with my local properties." It does NOT replace local properties — it adds to them. If the same property exists locally AND in config server, the config server value wins (external > embedded).

---

## 21. Debugging Lessons — Common Docker Issues

### 1. Git Bash (MINGW64) Path Mangling

**Symptom:** `docker exec kafka /opt/kafka/bin/script.sh` fails with "No such file or directory"

**Root cause:** Git Bash on Windows converts Unix-style absolute paths to Windows paths before passing them to commands. `/opt/kafka/bin/script.sh` becomes `C:/Program Files/Git/opt/kafka/bin/script.sh`.

**Fixes:**

```bash
# Option A: Wrap in sh -c (path stays as string argument)
docker exec kafka sh -c '/opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092'

# Option B: Disable path conversion for one command
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-topics.sh ...

# Option C: Set globally in script
export MSYS_NO_PATHCONV=1
```

### 2. DNS Resolution Failure Inside Containers

**Symptom:** `UnknownHostException: github.com` from config-server

**Root cause:** Corporate networks with proxies/firewalls may block DNS resolution from inside Docker containers. Docker Desktop uses a lightweight DNS forwarder that may not handle all corporate DNS configurations.

**Impact:** If the initial `git clone` succeeds (DNS worked at boot time), subsequent failures are harmless — configs are served from cache. If it fails at boot time, config-server can't serve anything.

**Fixes:**

- Configure Docker Desktop DNS (Settings → Docker Engine → `dns: ["8.8.8.8"]`)
- Set `refresh-rate: 3600` to reduce frequency of re-fetch attempts
- Corporate: add Docker network to proxy exceptions

### 3. Eureka Registration with Wrong Hostname

**Symptom:** Services register as `IE4LLT9F6RS54.mshome.net:8081` — gateway can't resolve

**Root cause:** On Windows with Hyper-V (Docker Desktop/WSL2), `InetAddress.getLocalHost().getHostName()` returns the Hyper-V virtual NAT adapter's hostname instead of `localhost`. Eureka uses this for registration.

**Fix in shared config (equitycart-config/application.yml):**

```yaml
eureka:
  instance:
    prefer-ip-address: true # register with IP instead of hostname
    # Do NOT set ip-address: 127.0.0.1 in Docker!
    # That would make each service advertise its OWN loopback.
    # Let Spring auto-detect the Docker container IP (172.18.0.x).
```

### 4. `spring.config.import` Ignoring Environment Variable

**Symptom:** Service logs show `Fetching config from server at: http://localhost:8888` despite Docker env var set

**Root cause:** The embedded `application.yml` had a hardcoded URL:

```yaml
# WRONG — this is fixed, env var can't override it:
spring.config.import: configserver:http://localhost:8888

# RIGHT — placeholder lets env var override:
spring.config.import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
```

**Key insight:** `spring.config.import` is NOT a standard Spring property that supports relaxed binding from env vars. It must use an explicit `${PLACEHOLDER}` to be configurable.

### 5. Orphan Container Warnings

**Symptom:** `Found orphan containers ([docker-api-gateway-1...]) for this project`

**Root cause:** Running `docker compose -f docker-pets.yml up` sees containers from `docker-compose-services.yml` that aren't defined in the current file. Docker Compose considers them "orphans" of the project.

**Fix:** Safe to ignore (informational warning). Suppress with `--remove-orphans` or by always referencing both files together:

```bash
docker compose -f docker-pets.yml -f docker-compose-services.yml up -d
```

### 6. Multiple Databases on One PostgreSQL Container

PostgreSQL's default entrypoint only creates ONE database (`POSTGRES_DB`). For multiple:

```bash
# init-db.sh (mounted as /docker-entrypoint-initdb.d/init-db.sh)
#!/bin/bash
set -e
for db in equitycart_user equitycart_order equitycart_product \
          equitycart_portfolio equitycart_ledger equitycart_notification; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE $db;
EOSQL
done
```

```yaml
# docker-pets.yml:
postgres:
  volumes:
    - ./init-db.sh:/docker-entrypoint-initdb.d/init-db.sh:ro
```

**Important:** `docker-entrypoint-initdb.d/` scripts only run on FIRST container creation (when the volume is empty). If you add a new database later, you must either:

- `docker compose down -v` + `up` (destroys all data), or
- `docker exec postgres psql -U postgres -c "CREATE DATABASE new_db;"`

---

## 22. Phase 9 Docker Learnings — Shared Networks, Volume Mounts, and Policy Constraints

### 22.1 Shared External Network for Split Compose

When infra (`docker-pets.yml`) and app services (`docker-compose-services.yml`) are started separately, each file creates its own default network unless overridden.

**Observed issue:** services could not reliably discover infra components by DNS name across compose boundaries.

**Fix pattern:**

1. Create one shared network (once): `docker network create equitycart-shared`
2. Mark it `external: true` in both compose files
3. Attach all relevant services to that network
4. In startup scripts, create the network if missing before `docker compose up`

This makes split startup deterministic and keeps service DNS stable.

### 22.2 Internal Port vs Host Port (Reinforced)

Intra-container communication must always use:

- `service-name:container-port`

Host-mapped ports are only for host tools (browser, curl from laptop, DBeaver, etc.).

### 22.3 Volume Mount Lessons from Observability

For log persistence and cross-tool inspection:

- Mount service log root from container to host-accessible path/volume.
- Keep rolling policy in application logging config to prevent unbounded growth.
- Use read-only mounts (`:ro`) where mutation is not required (configs/scripts).

Operationally, this enabled `core-loglens` usage without centralized log backend.

### 22.4 Enterprise Egress Constraint (Zscaler) and Image Pull Failures

**Observed issue:** pulls from `docker.elastic.co` (Kibana/Elastic stack) denied by policy.

**Practical handling pattern:**

- Classify as environment/infrastructure blocker, not app bug.
- Capture exact error evidence in learning/progress docs.
- Use a fallback architecture (structured logs + local log analysis tool).
- Keep blocked stack as deferred item for future network allowlisting.

### 22.5 Interview Questions

1. **"Why not use two independent default compose networks?"**  
   → Because cross-stack DNS and connectivity become brittle/impossible when services need to talk across files.

2. **"What is the trade-off of host-mounted logs vs centralized log shipping?"**  
   → Host mounts are simple and immediate for local/dev debugging; centralized shipping is stronger for search/retention at scale but depends on extra infra and network policy.

3. **"How do you make startup scripts idempotent for Docker network creation?"**  
   → Check existence first (`docker network inspect ...`) and create only if absent; reruns should not fail.
