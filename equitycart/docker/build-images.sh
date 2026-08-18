#!/bin/bash
set -e
cd "$(dirname "$0")/.."   # run from equitycart/

# Usage: ./docker/build-images.sh             (builds all)
#        ./docker/build-images.sh user-service (builds one)
# Optional override: IMAGE_PREFIX=mycorp ./docker/build-images.sh

IMAGE_PREFIX=${IMAGE_PREFIX:-equitycart}

build_image() {
  local module_name=$1   # Gradle project name  (e.g. user-service)
  local module_dir=$2    # Folder on disk        (e.g. user)
  local service_tag=$3   # Docker service name  (e.g. user-service)

  local image_name="${IMAGE_PREFIX}-${service_tag}:${IMAGE_TAG:-latest}"

  echo "==> Building $image_name ..."
  docker build -f docker/Dockerfile \
    --build-arg MODULE_NAME="$module_name" \
    --build-arg MODULE_DIR="$module_dir" \
    -t "$image_name" .
}

TARGET=${1:-all}

case "$TARGET" in
  discovery-server)   build_image discovery-server  discovery-server  discovery-server ;;
  config-server)      build_image config-server      config-server     config-server ;;
  api-gateway)        build_image api-gateway        api-gateway       api-gateway ;;
  user-service)       build_image user-service       user              user-service ;;
  order-service)      build_image order-service      order             order-service ;;
  portfolio-service)  build_image portfolio-service  portfolio         portfolio-service ;;
  product-service)    build_image product-service    product           product-service ;;
  market-data-service) build_image market-data-service market-data    market-data-service ;;
  ledger-service)     build_image ledger-service     ledger            ledger-service ;;
  notification-service) build_image notification-service notification  notification-service ;;
  all)
    build_image discovery-server    discovery-server  discovery-server
    build_image config-server       config-server     config-server
    build_image api-gateway         api-gateway       api-gateway
    build_image user-service        user              user-service
    build_image order-service       order             order-service
    build_image portfolio-service   portfolio         portfolio-service
    build_image product-service     product           product-service
    build_image market-data-service market-data       market-data-service
    build_image ledger-service      ledger            ledger-service
    build_image notification-service notification     notification-service
    ;;
  *) echo "Unknown module: $TARGET"; exit 1 ;;
esac

echo "Done. Images built successfully. Prefix used: ${IMAGE_PREFIX}"