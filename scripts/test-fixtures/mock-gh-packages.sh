#!/usr/bin/env bash
set -euo pipefail

arguments="$*"
case "$arguments" in
  *'/packages?package_type=maven'*)
    case "${MOCK_PACKAGE_STATE:-absent}" in
      absent) printf '[[]]\n' ;;
      present) printf '[[{"name":"com.getair.stremio-addon-client"}]]\n' ;;
      *) exit 2 ;;
    esac
    ;;
  *'/packages/maven/'*'/versions?'*)
    case "${MOCK_PACKAGE_STATE:-absent}" in
      absent) printf '[[]]\n' ;;
      present) printf '[[{"id":42,"name":"1.2.3"}]]\n' ;;
      *) exit 2 ;;
    esac
    ;;
  *) exit 2 ;;
esac
