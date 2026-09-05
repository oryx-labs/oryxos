#!/usr/bin/env bash
# Point this clone at .githooks/ so pre-push checks upstream freshness.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" config core.hooksPath .githooks
chmod +x "$ROOT/.githooks/pre-push" "$ROOT/scripts/sync-upstream.sh"
echo "Installed git hooks from .githooks (core.hooksPath)."
echo "pre-push will block when config/dependency-check-suppressions.xml or pom.xml lag main."
