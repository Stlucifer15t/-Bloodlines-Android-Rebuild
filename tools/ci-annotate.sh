#!/usr/bin/env bash
#
# ci-annotate.sh — surface a build log's useful slice as a single GitHub Actions annotation.
#
# Why: the raw job log is only downloadable from a signed blob-storage URL, which some
# environments cannot reach. Annotations come back through the normal GitHub API, so a failing
# build stays diagnosable everywhere. Newlines are escaped as %0A per the workflow-command rules.
#
# Usage: tools/ci-annotate.sh <log-file> [max-chars]
#
set -uo pipefail

LOG="${1:-gradle-build.log}"
LIMIT="${2:-12000}"

if [ ! -f "$LOG" ]; then
    echo "::error title=Build diagnostics::no build log was produced"
    exit 0
fi

PATTERN='^e: |^w: .*error|error:|^> Task .*FAILED|^FAILURE:|tests? completed, .*failed|FAILED$|What went wrong|^Caused by:|^\s+(at |Caused by)'
slice="$(grep -nE "$PATTERN" -A3 "$LOG" 2>/dev/null | head -c "$LIMIT" || true)"

if [ -z "$slice" ]; then
    slice="$(tail -c "$LIMIT" "$LOG")"
fi

escaped="$(printf '%s' "$slice" \
    | sed -e 's/\r//g' \
    | sed -e ':a' -e 'N' -e '$!ba' -e 's/%/%25/g' -e 's/\n/%0A/g')"

printf '::error title=Build diagnostics::%s\n' "$escaped"
