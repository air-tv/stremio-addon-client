#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
repository="${GITHUB_REPOSITORY:-}"
owner="${repository%%/*}"
gh_bin="${GH_BIN:-gh}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Preflight version must be stable MAJOR.MINOR.PATCH" >&2
  exit 1
fi
if [[ -z "${GH_TOKEN:-}" || -z "$owner" || "$owner" == "$repository" ]]; then
  echo "Preflight requires GH_TOKEN and GITHUB_REPOSITORY" >&2
  exit 1
fi

if ! packages_json="$(
  "$gh_bin" api --paginate --slurp "/orgs/$owner/packages?package_type=maven&per_page=100"
)"; then
  echo "Failed to enumerate Maven packages for $repository" >&2
  exit 1
fi

mapfile -t published_packages < <(jq -r '.[][] | .name' <<<"$packages_json" | sort -u)
mapfile -t expected_packages < <("$script_dir/release-maven-package-names.sh")

found=0
for package in "${expected_packages[@]}"; do
  if ! printf '%s\n' "${published_packages[@]}" | grep -Fqx -- "$package"; then
    continue
  fi
  encoded_package="$(jq -rn --arg value "$package" '$value|@uri')"
  if ! versions_json="$(
    "$gh_bin" api --paginate --slurp "/orgs/$owner/packages/maven/$encoded_package/versions?per_page=100"
  )"; then
    echo "Failed to enumerate versions for $package" >&2
    exit 1
  fi
  if jq -e --arg version "$version" 'any(.[][]; .name == $version)' <<<"$versions_json" >/dev/null; then
    echo "Release version $version already exists in $package; refusing to overwrite or recover it." >&2
    found=1
  fi
done

if (( found != 0 )); then
  echo "Use a new stable release tag. Existing successful package versions are never deleted by a rerun." >&2
  exit 1
fi

echo "Release version $version is absent from every expected Maven package."
