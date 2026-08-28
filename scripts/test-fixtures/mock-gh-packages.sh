#!/usr/bin/env bash
set -euo pipefail

arguments="$*"
case "$arguments" in
  *'/packages?package_type=maven'*)
    case "${MOCK_PACKAGE_STATE:-absent}" in
      absent) printf '[[]]\n' ;;
      present|malformed-versions|invalid-version-shape) printf '[[{"name":"com.getair.stremio-addon-client"}]]\n' ;;
      malformed-packages) printf '{not-json\n' ;;
      invalid-package-shape) printf '[[{"name":42}]]\n' ;;
      *) exit 2 ;;
    esac
    ;;
  *'/packages/maven/'*'/versions?'*)
    case "${MOCK_PACKAGE_STATE:-absent}" in
      absent) printf '[[]]\n' ;;
      present) printf '[[{"id":42,"name":"1.2.3"}]]\n' ;;
      malformed-versions) printf '{not-json\n' ;;
      invalid-version-shape) printf '[[{"id":"wrong-type","name":42}]]\n' ;;
      *) exit 2 ;;
    esac
    ;;
  *) exit 2 ;;
esac
