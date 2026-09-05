#!/usr/bin/env bash
# Merge oryx-labs/main into the current branch (or only check how far behind).
#
#   ./scripts/sync-upstream.sh          # fetch + merge
#   ./scripts/sync-upstream.sh --check  # exit 1 if OWASP/pom lag behind main
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

resolve_upstream() {
  local remote url
  for remote in origin upstream; do
    url="$(git remote get-url "$remote" 2>/dev/null || true)"
    if echo "$url" | grep -Eq 'github\.com[:/]oryx-labs/oryxos(\.git)?$'; then
      echo "$remote"
      return 0
    fi
  done
  if git remote get-url origin >/dev/null 2>&1; then
    echo origin
    return 0
  fi
  echo "No git remote found. Add origin pointing at oryx-labs/oryxos." >&2
  exit 1
}

CHECK_ONLY=0
if [[ "${1:-}" == "--check" ]]; then
  CHECK_ONLY=1
fi

UPSTREAM="$(resolve_upstream)"
git fetch "$UPSTREAM" main --quiet

if git merge-base --is-ancestor "$UPSTREAM/main" HEAD; then
  echo "Already contains $UPSTREAM/main."
  exit 0
fi

behind="$(git rev-list --count "HEAD..$UPSTREAM/main")"
echo "This branch is ${behind} commit(s) behind $UPSTREAM/main."

deps_lag=0
for f in config/dependency-check-suppressions.xml pom.xml; do
  if ! git diff --quiet "$UPSTREAM/main" -- "$f"; then
    echo "  lags: $f"
    deps_lag=1
  fi
done

if [[ "$CHECK_ONLY" -eq 1 ]]; then
  if [[ "$deps_lag" -eq 1 ]]; then
    echo "OWASP/deps files differ from $UPSTREAM/main." >&2
    echo "Run: make sync-upstream && mvn -B verify" >&2
    exit 1
  fi
  echo "OWASP/pom match main. Push allowed; merge soon to avoid the next CVE wave."
  exit 0
fi

git merge "$UPSTREAM/main" --no-edit
echo "Merged $UPSTREAM/main. Next: mvn -B verify"
