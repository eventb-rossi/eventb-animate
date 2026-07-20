#!/usr/bin/env bash
set -euo pipefail

JAR_PATH="${1:?Usage: smoke-release.sh <eventb-animate.jar>}"
if [ ! -f "$JAR_PATH" ]; then
  echo "ERROR: release jar not found: $JAR_PATH"
  exit 1
fi

JAVA_BIN="${JAVA:-java}"
SMOKE_TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-120}"
CLEAN_MODEL="src/test/resources/models/traffic-light/M2.bum"
VIOLATING_MODEL="src/test/resources/models/base-model/M1.bum"
SMOKE_DIRECTORY=$(mktemp -d "${TMPDIR:-/tmp}/eventb-animate-smoke.XXXXXX")
trap 'rm -rf "$SMOKE_DIRECTORY"' EXIT

TIMEOUT_COMMAND=()
if command -v timeout >/dev/null 2>&1; then
  TIMEOUT_COMMAND=(timeout --signal=TERM --kill-after=5 "$SMOKE_TIMEOUT_SECONDS")
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT_COMMAND=(gtimeout --signal=TERM --kill-after=5 "$SMOKE_TIMEOUT_SECONDS")
else
  echo "Warning: timeout command unavailable; relying on the workflow step deadline" >&2
fi

run_bounded() {
  "${TIMEOUT_COMMAND[@]}" "$@"
}

VERSION_OUTPUT=$(run_bounded "$JAVA_BIN" -jar "$JAR_PATH" --version)
VERSION="${VERSION_OUTPUT#eventb-animate }"
if [ -z "$VERSION" ] || [ "$VERSION_OUTPUT" != "eventb-animate $VERSION" ]; then
  echo "ERROR: unexpected --version output: $VERSION_OUTPUT"
  exit 1
fi

run_bounded "$JAVA_BIN" -jar "$JAR_PATH" "$CLEAN_MODEL"

REPORT="$SMOKE_DIRECTORY/report.json"
run_bounded "$JAVA_BIN" -jar "$JAR_PATH" \
  --states 1 --json "$REPORT" "$CLEAN_MODEL"

REPORT_VERSION=$(
  sed -n 's/.*"toolVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$REPORT"
)
if [ "$REPORT_VERSION" != "$VERSION" ]; then
  echo "ERROR: report toolVersion '$REPORT_VERSION' does not match '$VERSION'"
  exit 1
fi
if ! grep -Eq \
  '"reason"[[:space:]]*:[[:space:]]*"state_limit"' "$REPORT"; then
  echo "ERROR: bounded smoke report did not record state_limit"
  exit 1
fi

TRACE="$SMOKE_DIRECTORY/trace.json"
set +e
run_bounded "$JAVA_BIN" -jar "$JAR_PATH" --save "$TRACE" "$VIOLATING_MODEL"
SAVE_EXIT=$?
set -e
if [ "$SAVE_EXIT" -ne 1 ]; then
  echo "ERROR: trace-save smoke expected exit 1, got $SAVE_EXIT"
  exit 1
fi
if [ ! -s "$TRACE" ]; then
  echo "ERROR: trace-save smoke did not write a trace"
  exit 1
fi

run_bounded "$JAVA_BIN" -jar "$JAR_PATH" replay -t "$TRACE" "$VIOLATING_MODEL"
echo "Release smoke tests passed for eventb-animate $VERSION"
