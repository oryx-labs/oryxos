#!/usr/bin/env bash
# Package OryxOS changed files into a tar.gz and sync to remote server.
# Usage: ./scripts/package.sh [output_dir]

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
REMOTE_HOST="root@117.72.92.117"
REMOTE_DIR="/root/oryxos"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="${1:-$PROJECT_ROOT}"

# ── Logging helpers ────────────────────────────────────────────────────────────
info()  { echo "[INFO]  $*"; }
warn()  { echo "[WARN]  $*" >&2; }
error() { echo "[ERROR] $*" >&2; }

# ── Commit message builder ──────────────────────────────────────────────────────
# Derive a meaningful commit message from the changed / deleted file lists:
#   subject line summarises the affected top-level areas + file counts,
#   body lists the files (capped to keep the message readable).
build_commit_message() {
  local changed="$1" deleted="$2"
  local n_changed n_deleted areas subject
  local -i cap=30

  n_changed=$(printf '%s\n' "$changed" | grep -c '[^[:space:]]' || true)
  n_deleted=$(printf '%s\n' "$deleted" | grep -c '[^[:space:]]' || true)
  [[ "$n_changed" =~ ^[0-9]+$ ]] || n_changed=0
  [[ "$n_deleted" =~ ^[0-9]+$ ]] || n_deleted=0

  # Distinct top-level path segments touched (e.g. "docs, scripts, website")
  areas=$(printf '%s\n%s\n' "$changed" "$deleted" \
    | grep '[^[:space:]]' \
    | sed 's#/.*##' \
    | sort -u | paste -sd ', ' -)

  if [[ "$n_changed" -gt 0 && "$n_deleted" -gt 0 ]]; then
    subject="chore: sync ${areas:-changes} (${n_changed} changed, ${n_deleted} deleted)"
  elif [[ "$n_deleted" -gt 0 ]]; then
    subject="chore: remove ${n_deleted} file(s) in ${areas:-repo}"
  else
    subject="chore: update ${areas:-changes} (${n_changed} file(s))"
  fi

  printf '%s\n' "$subject"
  if [[ "$n_changed" -gt 0 ]]; then
    printf '\nChanged:\n'
    printf '%s\n' "$changed" | grep '[^[:space:]]' | head -n "$cap" | sed 's/^/  - /'
    [[ "$n_changed" -gt "$cap" ]] && printf '  - ... and %s more\n' "$((n_changed - cap))"
  fi
  if [[ "$n_deleted" -gt 0 ]]; then
    printf '\nDeleted:\n'
    printf '%s\n' "$deleted" | grep '[^[:space:]]' | head -n "$cap" | sed 's/^/  - /'
    [[ "$n_deleted" -gt "$cap" ]] && printf '  - ... and %s more\n' "$((n_deleted - cap))"
  fi
}

# ── Version / archive name ─────────────────────────────────────────────────────
VERSION=$(git -C "$PROJECT_ROOT" describe --tags --always --dirty 2>/dev/null || echo "dev")
TIMESTAMP=$(date +%Y%m%d%H%M%S)_$$
ARCHIVE="$OUTPUT_DIR/oryxos-${VERSION}-${TIMESTAMP}.tar.gz"

# Always remove the local archive on exit (success, failure, or signal)
trap 'rm -f "$ARCHIVE"' EXIT

# ── Branch detection ───────────────────────────────────────────────────────────
LOCAL_BRANCH=$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD)
info "Local branch: ${LOCAL_BRANCH}"

# ── Collect changed files ──────────────────────────────────────────────────────
# 1. Committed locally but not yet pushed
COMMITTED_FILES=$(git -C "$PROJECT_ROOT" diff --name-only --diff-filter=ACM \
  "origin/${LOCAL_BRANCH}" HEAD 2>/dev/null || true)

# 2. Modified in working tree but not committed
WORKDIR_FILES=$(git -C "$PROJECT_ROOT" diff --name-only --diff-filter=ACM \
  HEAD 2>/dev/null || true)

# 3. Untracked new files (entire repo, respects .gitignore)
UNTRACKED_FILES=$(git -C "$PROJECT_ROOT" ls-files --others --exclude-standard \
  2>/dev/null || true)

# 4. Files deleted locally that exist on origin → remove on remote
_DELETED_RAW=$(printf '%s\n%s\n%s' \
  "$(git -C "$PROJECT_ROOT" diff --name-only --diff-filter=D \
      "origin/${LOCAL_BRANCH}" HEAD 2>/dev/null || true)" \
  "$(git -C "$PROJECT_ROOT" diff --cached --name-only --diff-filter=D \
      2>/dev/null || true)" \
  "$(git -C "$PROJECT_ROOT" diff --name-only --diff-filter=D \
      2>/dev/null || true)" \
  | sort -u)
# Keep only files that are truly gone from disk
DELETED_FILES=""
while IFS= read -r f; do
  [[ -n "$f" && ! -e "$PROJECT_ROOT/$f" ]] && DELETED_FILES="${DELETED_FILES}${f}"$'\n'
done <<< "$_DELETED_RAW"
DELETED_FILES="${DELETED_FILES%$'\n'}"

# Combine, deduplicate, exclude archives and target/ directories
count_lines() { echo "${1}" | grep -c '[^[:space:]]' 2>/dev/null || echo 0; }

ALL_FILES=$(printf '%s\n%s\n%s' \
    "$COMMITTED_FILES" "$WORKDIR_FILES" "$UNTRACKED_FILES" \
  | grep -v '\.tar\.gz$' \
  | grep -v '^target/' \
  | grep -v '/target/' \
  | grep '[^[:space:]]' \
  | sort -u || true)

# ── Debug summary ──────────────────────────────────────────────────────────────
echo "--- File sources ---"
info "[committed vs origin] $(count_lines "$COMMITTED_FILES") file(s)"
echo "$COMMITTED_FILES" | grep '[^[:space:]]' | sed 's/^/  + /' || true
info "[workdir vs HEAD]     $(count_lines "$WORKDIR_FILES") file(s)"
echo "$WORKDIR_FILES"   | grep '[^[:space:]]' | sed 's/^/  ~ /' || true
info "[untracked]           $(count_lines "$UNTRACKED_FILES") file(s)"
echo "$UNTRACKED_FILES" | grep '[^[:space:]]' | sed 's/^/  ? /' || true
info "[deleted locally]     $(count_lines "$DELETED_FILES") file(s)"
echo "$DELETED_FILES"   | grep '[^[:space:]]' | sed 's/^/  - /' || true
echo "--------------------"

# ── Build archive ──────────────────────────────────────────────────────────────
if [[ -z "$ALL_FILES" ]]; then
  info "No changed files to package."
  SKIP_ARCHIVE=1
else
  SKIP_ARCHIVE=0
  FILE_COUNT=$(echo "$ALL_FILES" | grep -c '[^[:space:]]')
  info "Packaging ${FILE_COUNT} file(s):"
  echo "$ALL_FILES" | sed 's/^/  /'
  echo "$ALL_FILES" | tr '\n' '\0' \
    | COPYFILE_DISABLE=1 tar czf "$ARCHIVE" -C "$PROJECT_ROOT" --null -T -
  info "Archive created: $ARCHIVE ($(du -sh "$ARCHIVE" | cut -f1))"
fi

# ── Upload archive ─────────────────────────────────────────────────────────────
ARCHIVE_NAME="$(basename "$ARCHIVE")"
if [[ "${SKIP_ARCHIVE}" -eq 0 ]]; then
  info "Uploading to ${REMOTE_HOST}:${REMOTE_DIR} ..."
  scp "$ARCHIVE" "${REMOTE_HOST}:${REMOTE_DIR}/"
  info "Upload complete: ${REMOTE_HOST}:${REMOTE_DIR}/${ARCHIVE_NAME}"
fi

# ── Build remote delete commands (passed via env to avoid injection) ────────────
DELETED_LIST=$(printf '%s' "$DELETED_FILES" | tr '\n' ':')

# ── Build commit message (base64-encoded to survive multi-line transport) ───────
COMMIT_MSG=$(build_commit_message "$ALL_FILES" "$DELETED_FILES")
COMMIT_MSG_B64=$(printf '%s' "$COMMIT_MSG" | base64 | tr -d '\n')
info "Commit message:"
printf '%s\n' "$COMMIT_MSG" | sed 's/^/  | /'

# ── Remote: pull → extract → commit → push ────────────────────────────────────
info "Syncing remote ..."
ssh "${REMOTE_HOST}" \
  REMOTE_DIR="${REMOTE_DIR}" \
  LOCAL_BRANCH="${LOCAL_BRANCH}" \
  ARCHIVE_NAME="${ARCHIVE_NAME}" \
  SKIP_ARCHIVE="${SKIP_ARCHIVE}" \
  DELETED_LIST="${DELETED_LIST}" \
  COMMIT_MSG_B64="${COMMIT_MSG_B64}" \
  'bash -s' <<'REMOTE_SCRIPT'
set -euo pipefail

info()  { echo "[INFO]  $*"; }
error() { echo "[ERROR] $*" >&2; }

cd "${REMOTE_DIR}"

# Switch branch if needed
REMOTE_BRANCH=$(git rev-parse --abbrev-ref HEAD)
info "Remote branch: ${REMOTE_BRANCH}"
if [[ "${REMOTE_BRANCH}" != "${LOCAL_BRANCH}" ]]; then
  info "Switching to branch ${LOCAL_BRANCH} ..."
  git fetch origin
  git checkout "${LOCAL_BRANCH}" 2>/dev/null \
    || git checkout -b "${LOCAL_BRANCH}" "origin/${LOCAL_BRANCH}"
fi

# Pull latest before extracting so conflicts are resolved in order
info "Pulling origin/${LOCAL_BRANCH} ..."
git pull origin "${LOCAL_BRANCH}"

# Extract the uploaded archive (overwrites pulled content with local changes)
if [[ "${SKIP_ARCHIVE}" -eq 0 && -f "${ARCHIVE_NAME}" ]]; then
  info "Extracting ${ARCHIVE_NAME} ..."
  tar xzf "${ARCHIVE_NAME}" --warning=no-unknown-keyword
  rm -f "${ARCHIVE_NAME}"
fi

# Remove stale archives
find "${REMOTE_DIR}" -maxdepth 1 -name '*.tar.gz' -delete

# Remove files deleted locally
if [[ -n "${DELETED_LIST}" ]]; then
  info "Removing locally-deleted files on remote ..."
  IFS=':' read -ra del_files <<< "${DELETED_LIST}"
  for f in "${del_files[@]}"; do
    [[ -z "$f" ]] && continue
    rm -f -- "${REMOTE_DIR}/${f}"
    info "  Deleted: ${f}"
  done
fi

# Commit and push
git add -A
if git diff --cached --quiet; then
  info "Nothing to commit on remote."
else
  COMMIT_MSG=$(printf '%s' "${COMMIT_MSG_B64:-}" | base64 -d 2>/dev/null || true)
  [[ -z "${COMMIT_MSG}" ]] && COMMIT_MSG="chore: sync local changes"
  git commit -m "${COMMIT_MSG}"

  MAX_RETRIES=3
  RETRY=0
  DELAY=2
  until git push origin "${LOCAL_BRANCH}" 2>&1 | tee /tmp/push_output.txt; do
    PUSH_OUTPUT=$(cat /tmp/push_output.txt)
    if echo "${PUSH_OUTPUT}" | grep -qiE 'refusing|403|permission|scope|authentication|not allowed'; then
      error "Push permanently rejected (auth/permission error). Aborting."
      cat /tmp/push_output.txt
      exit 1
    fi
    RETRY=$((RETRY + 1))
    if [[ ${RETRY} -ge ${MAX_RETRIES} ]]; then
      error "Push failed after ${MAX_RETRIES} retries. Giving up."
      exit 1
    fi
    info "Push failed, retrying in ${DELAY}s (${RETRY}/${MAX_RETRIES}) ..."
    sleep "${DELAY}"
    DELAY=$((DELAY * 2))
  done

  if [[ ${RETRY} -eq 0 ]]; then
    info "Push succeeded."
  else
    info "Push succeeded after ${RETRY} retry/retries."
  fi
fi

info "Remote sync done."
REMOTE_SCRIPT

info "Remote sync complete."

# ── Local commit: stage exactly the packaged files ────────────────────────────
if [[ "${SKIP_ARCHIVE}" -eq 0 && -n "$ALL_FILES" ]]; then
  info "Committing packaged files locally ..."
  while IFS= read -r f; do
    [[ -n "$f" ]] && git -C "$PROJECT_ROOT" add -- "$f" 2>/dev/null || true
  done <<< "$ALL_FILES"

  if [[ -n "$DELETED_FILES" ]]; then
    while IFS= read -r f; do
      [[ -n "$f" ]] && git -C "$PROJECT_ROOT" rm --cached -- "$f" 2>/dev/null || true
    done <<< "$DELETED_FILES"
  fi

  if git -C "$PROJECT_ROOT" diff --cached --quiet; then
    info "Nothing to commit locally."
  else
    git -C "$PROJECT_ROOT" commit -m "$COMMIT_MSG"
    info "Local commit done. Unpackaged files remain unstaged."
  fi
fi

# Trap handles archive cleanup — no explicit rm needed here
info "All done."
