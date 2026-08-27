#!/usr/bin/env bash
set -euo pipefail

air_config_file="${AIR_INTEGRATION_CONFIG:-$HOME/.config/air-tv/integration.env}"
if [[ ! -f "$air_config_file" ]]; then
  echo "Missing Air integration configuration at the expected local path." >&2
  exit 1
fi

set -a
source "$air_config_file"
set +a
export AIR_RUN_LIVE_INTEGRATION=true

exec ./gradlew jvmTest --tests com.getair.stremio.LiveAddonIntegrationTest --rerun-tasks
