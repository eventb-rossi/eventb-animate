#!/usr/bin/env bash
#
# Download a released eventb-animate jar onto the runner.
#
# The checksum manifest is fetched *before* the jar, and nothing the manifest
# does not vouch for is left anywhere the caller will run it.
#
# Inputs (environment):
#   EVENTB_ANIMATE_VERSION  release tag, e.g. v6.3; empty or `latest` means latest
#   EVENTB_ANIMATE_REPO     optional, defaults to eventb-rossi/eventb-animate
#   RUNNER_TEMP             from the runner; where the jar lands
#   GITHUB_OUTPUT           optional; receives `version` and `jar-path`
set -euo pipefail

repo="${EVENTB_ANIMATE_REPO:-eventb-rossi/eventb-animate}"
release_tag="${EVENTB_ANIMATE_VERSION:-latest}"

if [ "$release_tag" = "latest" ]; then
  release_url=$(curl --silent --show-error --location --fail --retry 3 --retry-all-errors \
    --output /dev/null --write-out '%{url_effective}' \
    "https://github.com/${repo}/releases/latest")
  release_tag="${release_url##*/}"
fi

release_version="${release_tag#v}"
base="https://github.com/${repo}/releases/download/${release_tag}"
jar="eventb-animate-${release_version}.jar"
dest="${RUNNER_TEMP:-/tmp}/eventb-animate-${release_tag}"
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

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "version=${release_tag}"
    echo "jar-path=${dest}/${jar}"
  } >> "$GITHUB_OUTPUT"
fi
echo "installed eventb-animate ${release_tag} at ${dest}/${jar}"
