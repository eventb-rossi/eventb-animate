#!/usr/bin/env bash
#
# Install a released eventb-animate jar and launcher onto the runner.
#
# The checksum manifest is fetched *before* the jar, and nothing the manifest
# does not vouch for is left anywhere the caller will run it. A jar already at
# the destination — e.g. a cache restore — is reused only once the manifest
# vouches for it.
#
# Inputs (environment):
#   EVENTB_ANIMATE_VERSION  release tag, e.g. v6.6; empty or `latest` means latest
#   EVENTB_ANIMATE_REPO     optional, defaults to eventb-rossi/eventb-animate
#   EVENTB_ANIMATE_ADD_TO_PATH
#                           optional, `true` creates a launcher on GITHUB_PATH
#   EVENTB_ANIMATE_RESOLVE_ONLY
#                           optional, `true` prints the outputs for the
#                           resolved release and exits without installing
#   RUNNER_TEMP             from the runner; where the jar lands
#   GITHUB_OUTPUT           optional; receives `version`, `jar-path`, and
#                           `cache-key`
#   GITHUB_PATH             required when EVENTB_ANIMATE_ADD_TO_PATH is `true`
set -euo pipefail

repo="${EVENTB_ANIMATE_REPO:-eventb-rossi/eventb-animate}"
release_tag="${EVENTB_ANIMATE_VERSION:-latest}"
add_to_path="${EVENTB_ANIMATE_ADD_TO_PATH:-false}"
resolve_only="${EVENTB_ANIMATE_RESOLVE_ONLY:-false}"
version_pattern='v[0-9]+\.[0-9]+(\.[0-9]+)?(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?'

require_release_tag() {
  if ! [[ "$1" =~ ^${version_pattern}$ ]]; then
    echo "::error::eventb-animate version must be 'latest' or a release tag such as v6.6" >&2
    exit 1
  fi
}

case "$add_to_path" in
  true | false) ;;
  *)
    echo "::error::EVENTB_ANIMATE_ADD_TO_PATH must be 'true' or 'false'" >&2
    exit 1
    ;;
esac

case "$resolve_only" in
  true | false) ;;
  *)
    echo "::error::EVENTB_ANIMATE_RESOLVE_ONLY must be 'true' or 'false'" >&2
    exit 1
    ;;
esac

if [ "$release_tag" = "latest" ]; then
  release_url=$(curl --silent --show-error --location --fail --retry 3 --retry-all-errors \
    --output /dev/null --write-out '%{url_effective}' \
    "https://github.com/${repo}/releases/latest")
  release_tag="${release_url##*/}"
fi
require_release_tag "$release_tag"

release_version="${release_tag#v}"
base="https://github.com/${repo}/releases/download/${release_tag}"
jar="eventb-animate-${release_version}.jar"
dest="${RUNNER_TEMP:-/tmp}/eventb-animate-${release_tag}"

emit_outputs() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'version=%s\njar-path=%s\ncache-key=%s\n' \
      "$release_tag" \
      "$dest/$jar" \
      "eventb-animate-jar-${release_tag}" >> "$GITHUB_OUTPUT"
  fi
}

# The actions' cache step needs the resolved tag, the jar path, and the cache
# key before anything is downloaded; resolve-only stops here so `latest` is
# chased once and the cache is keyed on a release, never on the literal word.
if [ "$resolve_only" = true ]; then
  emit_outputs
  printf 'resolved eventb-animate release %s\n' "$release_tag"
  exit 0
fi

mkdir -p "$dest"

# Read from stdin so the tool prints no filename: given a path holding a
# backslash — every path on Windows — GNU coreutils escapes the name and marks
# the line with a leading `\`, which then never matches the manifest.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum < "$1" | cut -d' ' -f1
  else
    shasum -a 256 < "$1" | cut -d' ' -f1
  fi
}

# The manifest first: a release without one is not a release we can verify, and
# a missing manifest is also the clearest signal that the release does not exist.
#
# The status is read rather than left to `--fail`, because a reset connection
# and a 404 are different diagnoses: told that a release it can see does not
# exist, a caller downgrades a pin that was never the problem. `--retry` covers
# neither on its own — a 404 is not retried, and a transport error needs
# `--retry-all-errors`.
status=$(curl --silent --show-error --location --retry 3 --retry-all-errors \
  --write-out '%{http_code}' \
  --output "$dest/SHA256SUMS" "$base/SHA256SUMS") || status=""
if [ "$status" = 404 ]; then
  echo "::error::no eventb-animate release ${release_tag} with a SHA256SUMS manifest: the release does not exist, or predates the manifest added in v6.2" >&2
  exit 1
fi
if [ "$status" != 200 ]; then
  echo "::error::could not download the SHA256SUMS manifest for ${release_tag}: ${status:-the transfer failed}" >&2
  exit 1
fi

# Lines are `<sha256>  <asset>`; the binary-mode marker `*` may precede the name.
expected="$(awk -v want="$jar" '$NF == want || $NF == "*" want { print $1; exit }' "$dest/SHA256SUMS")"
if [ -z "$expected" ]; then
  echo "::error::${jar} is not listed in SHA256SUMS for ${release_tag}" >&2
  exit 1
fi

# A jar already at the destination — typically restored by the actions' cache
# step — is reused only once the manifest vouches for it; anything else is
# replaced by a fresh download, so a poisoned cache buys an attacker nothing.
if [ -f "$dest/$jar" ] && [ "$(sha256_of "$dest/$jar")" = "$expected" ]; then
  echo "verified cached ${jar} against SHA256SUMS; skipping download"
else
  rm -f "$dest/$jar"

  # Under a name nothing runs or globs for until it has been vouched for, so a
  # rejected download is never left sitting at the path callers are handed.
  curl --silent --show-error --location --fail --retry 3 --retry-all-errors \
    -o "$dest/$jar.part" "$base/$jar"

  actual="$(sha256_of "$dest/$jar.part")"
  if [ "$actual" != "$expected" ]; then
    rm -f "$dest/$jar.part"
    echo "::error::checksum mismatch for ${jar}: expected ${expected}, got ${actual}" >&2
    exit 1
  fi
  mv "$dest/$jar.part" "$dest/$jar"
fi

path_message=""
if [ "$add_to_path" = true ]; then
  launcher="$dest/eventb-animate"
  cat > "$launcher" <<EOF
#!/usr/bin/env bash
set -euo pipefail
launcher_dir="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
exec java -jar "\$launcher_dir/$jar" "\$@"
EOF
  chmod 0755 "$launcher"
  printf '%s\n' "$dest" >> "${GITHUB_PATH:?GITHUB_PATH is required when adding eventb-animate to PATH}"
  path_message=" and added its launcher to PATH"
fi

emit_outputs
printf 'installed eventb-animate %s at %s%s\n' \
  "$release_tag" \
  "$dest/$jar" \
  "$path_message"
