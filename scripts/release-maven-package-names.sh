#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' \
  com.getair.stremio-addon-client \
  com.getair.stremio-addon-client-android \
  com.getair.stremio-addon-client-jvm \
  com.getair.stremio-addon-client-js \
  com.getair.stremio-addon-client-wasm-js \
  com.getair.stremio-addon-client-linuxx64 \
  com.getair.stremio-addon-client-mingwx64 \
  com.getair.stremio-addon-client-macosx64 \
  com.getair.stremio-addon-client-macosarm64 \
  com.getair.stremio-addon-client-iosx64 \
  com.getair.stremio-addon-client-iosarm64 \
  com.getair.stremio-addon-client-iossimulatorarm64
