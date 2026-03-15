#!/usr/bin/env bash
# Usage: source e2e/setup.sh
# Starts Artifactory and exports the required env vars for integration tests.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Starting Artifactory..."
docker compose -f "$SCRIPT_DIR/docker-compose.yml" up -d

echo "==> Waiting for Artifactory to be healthy..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8081/artifactory/api/system/ping > /dev/null 2>&1; then
    echo "==> Artifactory is up!"
    break
  fi
  echo "  ...waiting ($i/30)"
  sleep 5
done

# Default Artifactory OSS admin credentials
export JFROG_URL="http://localhost:8082"
export JFROG_USERNAME="admin"
export JFROG_PASSWORD="password"
export JFROG_ADMIN_TOKEN=""

echo ""
echo "Environment variables exported:"
echo "  JFROG_URL=$JFROG_URL"
echo "  JFROG_USERNAME=$JFROG_USERNAME"
echo "  JFROG_PASSWORD=***"
echo ""
echo "To run integration tests:"
echo "  mvn verify -DskipITs=false -Dtest=NONE"
echo ""
echo "To run Jenkins locally with the plugin:"
echo "  mvn hpi:run -Djenkins.version=2.440.3"
