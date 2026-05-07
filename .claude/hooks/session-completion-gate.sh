#!/usr/bin/env bash
# NubBank BaaS — Session Completion Gate (Stop hook)
#
# Fires when Claude tries to stop responding. Blocks Stop if substantive
# code/config changes are present in the working tree but baas-log.md was
# not updated.
#
# Layered with check-versions-before-push.sh (PreToolUse:Bash):
#   - This script catches "forgot to log".
#   - check-versions-before-push.sh catches "logged but no version block".
#
# Stop hook protocol:
#   - Read JSON from stdin (drained, not used).
#   - Emit JSON {"decision":"block","reason":"..."} on stdout to block.
#   - Exit 0 in all cases (the JSON does the blocking).
#
# Bypass for an exceptional case: set CLAUDE_BAAS_GATE_SKIP=1 in the env.

set -uo pipefail

REPO_ROOT="${BAAS_GATE_REPO_ROOT:-/Users/razormvp/nubbank-baas}"
LOG_FILE="baas-log.md"

# Drain stdin (Stop hooks receive a JSON event we don't need).
cat >/dev/null 2>&1 || true

# Per-turn opt-out for emergencies.
if [ "${CLAUDE_BAAS_GATE_SKIP:-0}" = "1" ]; then
  exit 0
fi

# Fail open if not in this repo or not a git repo.
if ! cd "$REPO_ROOT" 2>/dev/null; then exit 0; fi
if ! git rev-parse --git-dir >/dev/null 2>&1; then exit 0; fi

# Collect every changed path: tracked-unstaged + tracked-staged + untracked.
ALL_CHANGES=$(
  {
    git diff --name-only 2>/dev/null
    git diff --name-only --cached 2>/dev/null
    git ls-files --others --exclude-standard 2>/dev/null
  } | sort -u
)

# Filter to substantive paths — code, schema, infra, CI, dependency manifests.
SUBSTANTIVE=$(echo "$ALL_CHANGES" \
  | grep -E '\.(java|kt|sql|sh|yml|yaml|properties|tsx?|jsx?|css|scss)$|(^|/)pom\.xml$|(^|/)Dockerfile$|^infrastructure/|^\.github/workflows/' \
  || true)

# Nothing substantive — silent no-op.
if [ -z "$SUBSTANTIVE" ]; then
  exit 0
fi

# baas-log.md is in the changeset → gate satisfied at this layer.
if echo "$ALL_CHANGES" | grep -qE "(^|/)${LOG_FILE}$"; then
  exit 0
fi

# Substantive work + no log update → block Stop with a system reminder.
COUNT=$(echo "$SUBSTANTIVE" | wc -l | tr -d ' ')
HEAD_LIST=$(echo "$SUBSTANTIVE" | head -10)

REASON="Session Completion Gate (/baas skill, Item 2): ${COUNT} substantive file(s) changed in the working tree but baas-log.md has not been updated.

Changed files (first 10):
${HEAD_LIST}

Before stopping, add a Session N entry at the TOP of the Change History section in ${REPO_ROOT}/${LOG_FILE}. The entry must include:
  - Session number, date, one-line summary
  - New/Updated Files table
  - Key Decisions / Known Gotchas
  - Build Verification (mvn test output)
  - Confirmed Platform Versions block — SHA from: git log --oneline -1 -- baas-engine/

See .claude/skills/baas/SKILL.md for the full 9-item Session Completion Gate.

Bypass for an exceptional case: set CLAUDE_BAAS_GATE_SKIP=1 in the shell env."

# Emit Stop-hook block JSON via python3 (safe JSON encoding for multi-line strings).
python3 - "$REASON" <<'PY'
import json, sys
print(json.dumps({"decision": "block", "reason": sys.argv[1]}))
PY

exit 0
