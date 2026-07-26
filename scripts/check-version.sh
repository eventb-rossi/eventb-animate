#!/usr/bin/env bash
set -euo pipefail

# Verify that a release tag matches build.gradle version and the README's versioned references.
# Usage: check-version.sh <tag>   (e.g. check-version.sh v4.0)

TAG="${1:?Usage: check-version.sh <tag>}"
TAG_VERSION="${TAG#v}"
VERSION_PATTERN='v[0-9]+\.[0-9]+(\.[0-9]+)?(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?'

if [[ ! "$TAG" =~ ^${VERSION_PATTERN}$ ]]; then
  echo "ERROR: release tag is not a supported semantic version: $TAG"
  exit 1
fi

GRADLE_VERSION=$(sed -n "s/^version = '\\([^']*\\)'$/\\1/p" build.gradle)
if [ "$GRADLE_VERSION" != "$TAG_VERSION" ]; then
  echo "ERROR: build.gradle version '$GRADLE_VERSION' does not match tag '$TAG' (expected '$TAG_VERSION')"
  exit 1
fi

EXPECTED_README_REFERENCES=(
  "- uses: eventb-rossi/eventb-animate@${TAG}"
  "    version: '${TAG}'"
  "  - remote: 'https://raw.githubusercontent.com/eventb-rossi/eventb-animate/${TAG}/.gitlab-ci-template.yml'"
  "| \`EVENTB_ANIMATE_VERSION\` | Release version tag (e.g., \`${TAG}\`) | \`latest\` |"
)

for expected in "${EXPECTED_README_REFERENCES[@]}"; do
  if ! grep -Fq -- "$expected" README.md; then
    echo "ERROR: README.md is missing expected versioned reference: $expected"
    exit 1
  fi
done

# Every knob the two CI integrations accept must have a row in the matching
# README table, so a new input or variable cannot ship undocumented.
check_documented() {
  KIND="$1"
  NAMES="$2"
  if [ -z "$NAMES" ]; then
    echo "ERROR: found no $KIND; the extraction in check-version.sh is stale"
    exit 1
  fi
  while IFS= read -r name; do
    if ! grep -Fq -- "| \`${name}\` |" README.md; then
      echo "ERROR: README.md does not document the $KIND: $name"
      exit 1
    fi
  done <<< "$NAMES"
}

ACTION_INPUTS=$(
  sed -n '/^inputs:$/,/^runs:$/p' action.yml \
    | sed -n 's/^  \([a-z0-9-]\{1,\}\):$/\1/p'
)
check_documented "GitHub Action input" "$ACTION_INPUTS"

TEMPLATE_VARIABLES=$(
  sed -n '/^  variables:$/,/^  script:$/p' .gitlab-ci-template.yml \
    | sed -n 's/^    \(EVENTB_ANIMATE_[A-Z_]\{1,\}\):.*/\1/p'
)
check_documented "GitLab CI variable" "$TEMPLATE_VARIABLES"

README_TAGS=$(
  grep -oE "$VERSION_PATTERN" \
    README.md | sort -u || true
)
STALE_TAGS=$(printf '%s\n' "$README_TAGS" | grep -vxF -- "$TAG" || true)
if [ -n "$STALE_TAGS" ]; then
  echo "ERROR: README.md contains stale version tags:"
  while IFS= read -r stale_tag; do
    printf '  %s\n' "$stale_tag"
  done <<< "$STALE_TAGS"
  exit 1
fi

while IFS= read -r example; do
  EXAMPLE_VERSION=$(
    sed -n 's/.*"toolVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      "$example"
  )
  if [ "$EXAMPLE_VERSION" != "$TAG_VERSION" ]; then
    echo "ERROR: $example has toolVersion '$EXAMPLE_VERSION' (expected '$TAG_VERSION')"
    exit 1
  fi
done < <(
  find docs/examples -type f -name 'json-report-v3-*.json' | LC_ALL=C sort
)

CHANGELOG_HEADING=$(grep -F "## [$TAG_VERSION] - " CHANGELOG.md || true)
if ! printf '%s\n' "$CHANGELOG_HEADING" \
  | grep -Eq '^## \[[^]]+\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$'; then
  echo "ERROR: CHANGELOG.md has no dated [$TAG_VERSION] release heading"
  exit 1
fi

echo "Version check passed for $TAG: build, docs, examples, and changelog agree"
