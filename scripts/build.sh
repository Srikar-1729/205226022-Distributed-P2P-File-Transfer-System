#!/usr/bin/env bash
# Builds every module into a runnable fat jar: */target/<module>.jar
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q package "$@"
ls -1 */target/*-service.jar peer-node/target/peer-node.jar
