#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
repository="${GITHUB_REPOSITORY:-}"
owner="${repository%%/*}"
repo_name="${repository#*/}"

if [[ ! "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Cleanup version must be stable MAJOR.MINOR.PATCH" >&2
  exit 1
fi
if [[ -z "${GH_TOKEN:-}" || -z "$owner" || "$owner" == "$repository" || -z "$repo_name" ]]; then
  echo "Cleanup requires GH_TOKEN and GITHUB_REPOSITORY" >&2
  exit 1
fi

manual_recovery() {
  cat >&2 <<EOF
Automatic package recovery did not complete.
Manually delete every Maven package version $version linked to $repository at:
https://github.com/orgs/$owner/packages?repo_name=$repo_name
Do not rerun the release workflow until all partial versions are gone.
EOF
}

if ! packages_json="$(
  gh api --paginate --slurp "/orgs/$owner/packages?package_type=maven&per_page=100"
)"; then
  echo "Failed to enumerate Maven packages for $repository" >&2
  manual_recovery
  exit 1
fi

mapfile -t packages < <(
  jq -r --arg repository "$repository" '
    .[][]
    | select((.repository.full_name // $repository) == $repository)
    | .name
  ' <<<"$packages_json" |
    while IFS= read -r package; do
      case "$package" in
        com.getair.stremio-addon-client|com.getair.stremio-addon-client-*|stremio-addon-client|stremio-addon-client-*)
          printf '%s\n' "$package"
          ;;
      esac
    done
)

failures=0
for package in "${packages[@]}"; do
  encoded_package="$(jq -rn --arg value "$package" '$value|@uri')"
  if ! versions_json="$(
    gh api --paginate --slurp "/orgs/$owner/packages/maven/$encoded_package/versions?per_page=100"
  )"; then
    echo "Failed to enumerate versions for $package" >&2
    failures=$((failures + 1))
    continue
  fi
  mapfile -t version_ids < <(
    jq -r --arg version "$version" '.[][] | select(.name == $version) | .id' <<<"$versions_json"
  )
  for version_id in "${version_ids[@]}"; do
    echo "Deleting $package version $version"
    if ! gh api --method DELETE "/orgs/$owner/packages/maven/$encoded_package/versions/$version_id"; then
      echo "Failed to delete $package version id $version_id" >&2
      failures=$((failures + 1))
    fi
  done
done

remaining=0
for package in "${packages[@]}"; do
  encoded_package="$(jq -rn --arg value "$package" '$value|@uri')"
  if ! versions_json="$(
    gh api --paginate --slurp "/orgs/$owner/packages/maven/$encoded_package/versions?per_page=100"
  )"; then
    echo "Failed to verify versions for $package" >&2
    remaining=$((remaining + 1))
    continue
  fi
  if jq -e --arg version "$version" 'any(.[][]; .name == $version)' <<<"$versions_json" >/dev/null; then
    echo "Package cleanup remains incomplete for $package version $version" >&2
    remaining=$((remaining + 1))
  fi
done

if (( failures > 0 || remaining > 0 )); then
  manual_recovery
  exit 1
fi

echo "Package recovery is complete for $repository version $version"
