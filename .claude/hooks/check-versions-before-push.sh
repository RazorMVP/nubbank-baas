#!/usr/bin/env bash
# NubBank BaaS pre-push version guard
# Reads the tool input from stdin (JSON) and checks whether the command
# about to be executed is a git push. If it is, verifies that both
# baas-log.md and CLAUDE.md contain a "Confirmed Platform Versions" section.
# Exits 2 (blocking) with an error message if the check fails.
# Exits 0 to allow the command through for all other Bash calls.

set -euo pipefail

REPO_ROOT="/Users/razormvp/nubbank-baas"
LOG_FILE="$REPO_ROOT/baas-log.md"
CLAUDE_FILE="$REPO_ROOT/CLAUDE.md"
REQUIRED_STRING="Confirmed Platform Versions"

# Read the full JSON input from stdin
INPUT="$(cat)"

# Extract the command field
COMMAND="$(echo "$INPUT" | grep -o '"command":"[^"]*"' | head -1 | sed 's/"command":"//;s/"//')"

# Only gate on git push commands
if echo "$COMMAND" | grep -qE '^git push'; then

  MISSING=()

  if ! grep -q "$REQUIRED_STRING" "$LOG_FILE" 2>/dev/null; then
    MISSING+=("baas-log.md")
  fi

  if ! grep -q "$REQUIRED_STRING" "$CLAUDE_FILE" 2>/dev/null; then
    MISSING+=("CLAUDE.md")
  fi

  if [ ${#MISSING[@]} -gt 0 ]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║         NubBank BaaS VERSION RECORD GATE — PUSH BLOCKED     ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    echo "  git push is not allowed until platform versions are recorded."
    echo ""
    echo "  Missing 'Confirmed Platform Versions' section in:"
    for f in "${MISSING[@]}"; do
      echo "    ✗  $f"
    done
    echo ""
    echo "  Steps to unblock:"
    echo "    1. Run: git log --oneline -1 -- baas-engine/"
    echo "    2. Read versions from baas-engine/pom.xml"
    echo "    3. Add a 'Confirmed Platform Versions' table to the current"
    echo "       session entry in baas-log.md"
    echo "    4. Update the '## Confirmed Platform Versions' section"
    echo "       near the top of CLAUDE.md"
    echo "    5. Commit both files, then re-run git push"
    echo ""
    echo "  See /baas skill Session Completion Gate for the full template."
    echo ""
    # Exit code 2 = block the tool call
    exit 2
  fi

fi

# All other commands (or push checks passed) — allow through
exit 0
