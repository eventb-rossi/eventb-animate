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

# Before the setup action's first tagged release, the development README must
# point at main rather than claim that an older tag contains setup/action.yml.
# A not-yet-created release tag still uses its future exact ref, so the release
# commit can be checked before the tag is created.
SETUP_ACTION_REF="$TAG"
if git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null &&
   ! git cat-file -e "${TAG}:setup/action.yml" 2>/dev/null; then
  SETUP_ACTION_REF=main
fi

EXPECTED_README_REFERENCES=(
  "- uses: eventb-rossi/eventb-animate@${TAG}"
  "  uses: eventb-rossi/eventb-animate/setup@${SETUP_ACTION_REF}"
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

# The actions' own input documentation is not covered by the README scan below,
# and drifts silently when it is not gated: the root action's example tag read
# "v5.1" through four releases.
for action in action.yml setup/action.yml; do
  if ! grep -Fq -- "(e.g., \"${TAG}\")" "$action"; then
    echo "ERROR: $action does not use $TAG as the example release tag"
    exit 1
  fi
done

# Every knob the CI integrations accept must have a row in the matching README
# table, so a new input or variable cannot ship undocumented.
check_documented() {
  KIND="$1"
  NAMES="$2"
  DOCUMENTATION="$3"
  if [ -z "$NAMES" ]; then
    echo "ERROR: found no $KIND; the extraction in check-version.sh is stale"
    exit 1
  fi
  while IFS= read -r name; do
    if ! grep -Fq -- "| \`${name}\` |" <<< "$DOCUMENTATION"; then
      echo "ERROR: README.md does not document the $KIND: $name"
      exit 1
    fi
  done <<< "$NAMES"
}

ACTION_INPUTS=$(
  sed -n '/^inputs:$/,/^runs:$/p' action.yml \
    | sed -n 's/^  \([a-z0-9-]\{1,\}\):$/\1/p'
)
ACTION_INPUT_DOCUMENTATION=$(
  sed -n '/^#### Inputs$/,/^#### Set up the CLI for later steps$/p' README.md
)
check_documented "GitHub Action input" "$ACTION_INPUTS" "$ACTION_INPUT_DOCUMENTATION"

SETUP_ACTION_INPUTS=$(
  sed -n '/^inputs:$/,/^outputs:$/p' setup/action.yml \
    | sed -n 's/^  \([a-z0-9-]\{1,\}\):$/\1/p'
)
SETUP_ACTION_INPUT_DOCUMENTATION=$(
  sed -n '/^#### Set up the CLI for later steps$/,/^#### Examples$/p' README.md
)
check_documented \
  "GitHub setup action input" \
  "$SETUP_ACTION_INPUTS" \
  "$SETUP_ACTION_INPUT_DOCUMENTATION"

TEMPLATE_VARIABLES=$(
  sed -n '/^  variables:$/,/^  script:$/p' .gitlab-ci-template.yml \
    | sed -n 's/^    \(EVENTB_ANIMATE_[A-Z_]\{1,\}\):.*/\1/p'
)
check_documented "GitLab CI variable" "$TEMPLATE_VARIABLES" "$(cat README.md)"

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

# Matched by format version rather than a pinned one: a format bump renames every
# example, and a stale glob would silently check nothing instead of failing.
REPORT_EXAMPLES=$(
  find docs/examples -type f -name 'json-report-v*-*.json' | LC_ALL=C sort
)
if [ -z "$REPORT_EXAMPLES" ]; then
  echo "ERROR: found no report examples; the extraction in check-version.sh is stale"
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
done <<< "$REPORT_EXAMPLES"

CHANGELOG_HEADING=$(grep -F "## [$TAG_VERSION] - " CHANGELOG.md || true)
if ! printf '%s\n' "$CHANGELOG_HEADING" \
  | grep -Eq '^## \[[^]]+\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$'; then
  echo "ERROR: CHANGELOG.md has no dated [$TAG_VERSION] release heading"
  exit 1
fi

echo "Version check passed for $TAG: build, docs, examples, and changelog agree"
