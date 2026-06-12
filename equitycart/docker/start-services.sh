#!/bin/bash
set -e
cd "$(dirname "$0")/.."   # ← always runs from equitycart/

docker compose -f docker/docker-pets.yml -f docker/docker-compose-services.yml up -d \
  discovery config-server

until curl -s http://localhost:8761/actuator/health | grep -q '"status":"UP"'; do
  echo "    discovery not ready, retrying in 5s..."; sleep 5
done

until curl -s http://localhost:8888/actuator/health | grep -q '"status":"UP"'; do
  echo "    config-server not ready, retrying in 5s..."; sleep 5
done

docker compose -f docker/docker-pets.yml -f docker/docker-compose-services.yml up -d
echo "All services started. Check Eureka: http://localhost:8761"