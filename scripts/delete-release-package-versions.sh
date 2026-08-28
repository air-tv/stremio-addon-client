#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
repository="${GITHUB_REPOSITORY:-}"
owner="${repository%%/*}"
repo_name="${repository#*/}"
gh_bin="${GH_BIN:-gh}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

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
  "$gh_bin" api --paginate --slurp "/orgs/$owner/packages?package_type=maven&per_page=100"
)"; then
  echo "Failed to enumerate Maven packages for $repository" >&2
  manual_recovery
  exit 1
fi

if ! published_package_lines="$(
  jq -r '
    if type == "array"
       and all(.[]; type == "array")
       and all(.[][]; type == "object" and (.name | type == "string"))
    then [.[][] | .name] | unique[]
    else error("invalid GitHub Maven package response")
    end
  ' <<<"$packages_json"
)"; then
  echo "Invalid Maven package response for $repository" >&2
  manual_recovery
  exit 1
fi
published_packages=()
if [[ -n "$published_package_lines" ]]; then
  mapfile -t published_packages <<<"$published_package_lines"
fi

if ! expected_package_lines="$("$script_dir/release-maven-package-names.sh")"; then
  echo "Failed to enumerate expected Maven packages" >&2
  manual_recovery
  exit 1
fi
mapfile -t expected_packages <<<"$expected_package_lines"
if ! expected_unique_count="$(printf '%s\n' "${expected_packages[@]}" | sort -u | wc -l)"; then
  echo "Failed to validate expected Maven packages" >&2
  manual_recovery
  exit 1
fi
if (( ${#expected_packages[@]} != 12 )) ||
   [[ "$expected_unique_count" -ne 12 ]] ||
   printf '%s\n' "${expected_packages[@]}" | grep -Eq '^$'; then
  echo "Expected Maven package enumerator must return exactly 12 unique non-empty names" >&2
  manual_recovery
  exit 1
fi
packages=()
for package in "${expected_packages[@]}"; do
  if printf '%s\n' "${published_packages[@]}" | grep -Fqx -- "$package"; then
    packages+=("$package")
  fi
done

failures=0
for package in "${packages[@]}"; do
  encoded_package="$(jq -rn --arg value "$package" '$value|@uri')"
  if ! versions_json="$(
    "$gh_bin" api --paginate --slurp "/orgs/$owner/packages/maven/$encoded_package/versions?per_page=100"
  )"; then
    echo "Failed to enumerate versions for $package" >&2
    failures=$((failures + 1))
    continue
  fi
  if ! version_id_lines="$(
    jq -r --arg version "$version" '
      if type == "array"
         and all(.[]; type == "array")
         and all(.[][]; type == "object" and (.name | type == "string") and (.id | type == "number") and .id >= 0 and .id == (.id | floor))
      then .[][] | select(.name == $version) | .id
      else error("invalid GitHub Maven version response")
      end
    ' <<<"$versions_json"
  )"; then
    echo "Invalid Maven version response for $package" >&2
    failures=$((failures + 1))
    continue
  fi
  version_ids=()
  if [[ -n "$version_id_lines" ]]; then
    mapfile -t version_ids <<<"$version_id_lines"
  fi
  for version_id in "${version_ids[@]}"; do
    echo "Deleting $package version $version"
    if ! "$gh_bin" api --method DELETE "/orgs/$owner/packages/maven/$encoded_package/versions/$version_id"; then
      echo "Failed to delete $package version id $version_id" >&2
      failures=$((failures + 1))
    fi
  done
done

remaining=0
for package in "${packages[@]}"; do
  encoded_package="$(jq -rn --arg value "$package" '$value|@uri')"
  if ! versions_json="$(
    "$gh_bin" api --paginate --slurp "/orgs/$owner/packages/maven/$encoded_package/versions?per_page=100"
  )"; then
    echo "Failed to verify versions for $package" >&2
    remaining=$((remaining + 1))
    continue
  fi
  if ! version_exists="$(
    jq -r --arg version "$version" '
      if type == "array"
         and all(.[]; type == "array")
         and all(.[][]; type == "object" and (.name | type == "string") and (.id | type == "number") and .id >= 0 and .id == (.id | floor))
      then any(.[][]; .name == $version) | tostring
      else error("invalid GitHub Maven version response")
      end
    ' <<<"$versions_json"
  )"; then
    echo "Invalid Maven version response while verifying $package" >&2
    remaining=$((remaining + 1))
    continue
  fi
  if [[ "$version_exists" == "true" ]]; then
    echo "Package cleanup remains incomplete for $package version $version" >&2
    remaining=$((remaining + 1))
  fi
done

if (( failures > 0 || remaining > 0 )); then
  manual_recovery
  exit 1
fi

echo "Package recovery is complete for $repository version $version"
