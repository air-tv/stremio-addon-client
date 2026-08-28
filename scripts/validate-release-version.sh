#!/usr/bin/env bash
set -euo pipefail

tag="${1:-}"
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Release tag must be stable vMAJOR.MINOR.PATCH" >&2
  exit 1
fi

version="${tag#v}"
printf '%s\n' "$version"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'version=%s\n' "$version" >> "$GITHUB_OUTPUT"
fi
