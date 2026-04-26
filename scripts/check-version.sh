#!/usr/bin/env bash
set -euo pipefail

# Verify that a release tag matches build.gradle version and the README's versioned references.
# Usage: check-version.sh <tag>   (e.g. check-version.sh v4.0)

TAG="${1:?Usage: check-version.sh <tag>}"
TAG_VERSION="${TAG#v}"

GRADLE_VERSION=$(sed -n "s/^version = '\\([^']*\\)'$/\\1/p" build.gradle)
if [ "$GRADLE_VERSION" != "$TAG_VERSION" ]; then
  echo "ERROR: build.gradle version '$GRADLE_VERSION' does not match tag '$TAG' (expected '$TAG_VERSION')"
  exit 1
fi

EXPECTED_README_REFERENCES=(
  "- uses: eventb-rossi/animate@${TAG}"
  "    version: '${TAG}'"
  "  - remote: 'https://raw.githubusercontent.com/eventb-rossi/animate/${TAG}/.gitlab-ci-template.yml'"
  "| \`ANIMATE_VERSION\` | Release version tag (e.g., \`${TAG}\`) | \`latest\` |"
)

for expected in "${EXPECTED_README_REFERENCES[@]}"; do
  if ! grep -Fq -- "$expected" README.md; then
    echo "ERROR: README.md is missing expected versioned reference: $expected"
    exit 1
  fi
done

README_TAGS=$(grep -oE 'v[0-9]+\.[0-9]+' README.md | sort -u || true)
STALE_TAGS=$(printf '%s\n' "$README_TAGS" | grep -vx "$TAG" || true)
if [ -n "$STALE_TAGS" ]; then
  echo "ERROR: README.md contains stale version tags:"
  printf '  %s\n' $STALE_TAGS
  exit 1
fi

echo "Version check passed: build.gradle=$GRADLE_VERSION, README.md references are up to date"
