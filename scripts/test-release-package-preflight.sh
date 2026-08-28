#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mock_gh="$script_dir/test-fixtures/mock-gh-packages.sh"
export GH_TOKEN=test-token
export GITHUB_REPOSITORY=air-tv/stremio-addon-client
export GH_BIN="$mock_gh"

MOCK_PACKAGE_STATE=absent "$script_dir/assert-release-package-version-available.sh" 1.2.3 >/dev/null
if MOCK_PACKAGE_STATE=present "$script_dir/assert-release-package-version-available.sh" 1.2.3 >/dev/null 2>&1; then
  echo "Preflight accepted an existing package version" >&2
  exit 1
fi
MOCK_PACKAGE_STATE=absent "$script_dir/delete-release-package-versions.sh" 1.2.3 >/dev/null
if MOCK_PACKAGE_STATE=present "$script_dir/delete-release-package-versions.sh" 1.2.3 >/dev/null 2>&1; then
  echo "Cleanup reported success while the exact version remained" >&2
  exit 1
fi

workflow="$script_dir/../.github/workflows/publish.yml"
grep -Fq "needs.publish-linux.result == 'cancelled'" "$workflow"
grep -Fq "needs.publish-apple.result == 'cancelled'" "$workflow"
grep -Fq "needs.publish-windows.result == 'cancelled'" "$workflow"
grep -Fq "needs.root-metadata.result == 'cancelled'" "$workflow"
if grep -Fq 'always() && failure()' "$workflow"; then
  echo "Recovery must not depend on global failure()" >&2
  exit 1
fi

echo "Release package preflight fixtures passed"
