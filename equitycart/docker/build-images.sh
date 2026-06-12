#!/bin/bash
set -e
cd "$(dirname "$0")/.."   # run from equitycart/

# Usage: ./docker/build-images.sh             (builds all)
#        ./docker/build-images.sh user-service (builds one)

build_image() {
  local module_name=$1   # Gradle project name  (e.g. user-service)
  local module_dir=$2    # Folder on disk        (e.g. user)
  local image_name=$3    # Docker image tag      (e.g. user-service:latest)

  echo "==> Building $image_name ..."
  docker build -f docker/Dockerfile \
    --build-arg MODULE_NAME="$module_name" \
    --build-arg MODULE_DIR="$module_dir" \
    -t "$image_name" .
}

TARGET=${1:-all}

case "$TARGET" in
  discovery-server)   build_image discovery-server  discovery-server  discovery-server:latest ;;
  config-server)      build_image config-server      config-server     config-server:latest ;;
  api-gateway)        build_image api-gateway        api-gateway       api-gateway:latest ;;
  user-service)       build_image user-service       user              user-service:latest ;;
  order-service)      build_image order-service      order             order-service:latest ;;
  portfolio-service)  build_image portfolio-service  portfolio         portfolio-service:latest ;;
  product-service)    build_image product-service    product           product-service:latest ;;
  market-data-service) build_image market-data-service market-data    market-data-service:latest ;;
  ledger-service)     build_image ledger-service     ledger            ledger-service:latest ;;
  notification-service) build_image notification-service notification  notification-service:latest ;;
  all)
    build_image discovery-server    discovery-server  discovery-server:latest
    build_image config-server       config-server     config-server:latest
    build_image api-gateway         api-gateway       api-gateway:latest
    build_image user-service        user              user-service:latest
    build_image order-service       order             order-service:latest
    build_image portfolio-service   portfolio         portfolio-service:latest
    build_image product-service     product           product-service:latest
    build_image market-data-service market-data       market-data-service:latest
    build_image ledger-service      ledger            ledger-service:latest
    build_image notification-service notification     notification-service:latest
    ;;
  *) echo "Unknown module: $TARGET"; exit 1 ;;
esac

echo "Done. Images built successfully."