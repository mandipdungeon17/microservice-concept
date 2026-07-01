#!/bin/bash
set -e
cd "$(dirname "$0")/.."   # ← always runs from equitycart/

echo "==> Starting infrastructure (docker-pets.yml)..."
docker compose -f docker/docker-pets.yml up -d

echo "==> Waiting for PostgreSQL to be ready..."
until docker exec postgres pg_isready -U postgres > /dev/null 2>&1; do
  echo "    postgres not ready, retrying in 3s..."; sleep 3
done

echo "==> Waiting for Kafka to be ready..."
until docker exec kafka sh -c '/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092' > /dev/null 2>&1; do
  echo "    kafka not ready, retrying in 3s..."; sleep 3
done

echo "==> Waiting for Keycloak to be ready..."
until curl -sf http://localhost:8180/realms/equitycart/.well-known/openid-configuration > /dev/null 2>&1; do
  echo "    keycloak not ready, retrying in 5s..."; sleep 5
done

echo "Infrastructure is up. Run ./docker/start-services.sh to start application services."