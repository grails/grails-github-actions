#!/bin/bash
#
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.
#

# Action will publish the specified documentation to the specified documentation branch

set_value_or_error() {
  local value="$1"
  local defaultValue="$2"
  local variableName="$3"
  local validValues="${4:-}"  # optional argument (if empty, skip validation)

  if [[ -z "$value" && -z "$defaultValue" ]]; then
    echo "ERROR: A value for $variableName is required." >&2
    exit 1
  fi

  if [[ -n "$value" ]]; then    
    decidedValue="$value"
  else
    echo "${variableName}: Using default value: ${defaultValue}"
    decidedValue="$defaultValue"
  fi

  if [[ -n "$validValues" ]]; then
    local match=false
    local v
    for v in $validValues; do
      if [[ "$decidedValue" == "$v" ]]; then
        match=true
        break
      fi
    done

    if ! $match; then
      echo "ERROR: Invalid value for '$variableName': '$decidedValue'." >&2
      echo "       Must be one of: $validValues" >&2
      return 1
    fi
  fi

  eval "$variableName=\"\$decidedValue\""
}

set_path_value_or_error() {
  local variableName="$3"
  set_value_or_error "$@"

  if [[ "${decidedValue}" == /* || "${decidedValue}" == ./* ]]; then
    echo "ERROR: ${variableName} must not start with '/' or './'. Got: '${decidedValue}'"
    exit 1
  fi
}

publish_artifacts() {
  if [[ -z "${SOURCE_FOLDER}" || -z "${PUBLISH_PATH}" || -z "${BASE_PUBLISH_PATH}" ]]; then
    echo "ERROR: SOURCE_FOLDER(${SOURCE_FOLDER}), PUBLISH_PATH(${PUBLISH_PATH}), BASE_PUBLISH_PATH(${BASE_PUBLISH_PATH}) must be set."
    return 1
  fi

  if [[ "${PURGE_EXISTING}" == "true" ]]; then
    local purgePath
    if [[ "${PURGE_BY_BASE_PATH}" == "false" ]]; then
      purgePath="${PUBLISH_PATH}"
    else
      purgePath="${BASE_PUBLISH_PATH}"
    fi

    if [[ -d "${purgePath}" ]]; then
      if [[ "$(ls -A "${purgePath}" 2>/dev/null)" ]]; then
        echo "Removing existing directory at ${purgePath}"
        git rm -rf "${purgePath}"
      fi
    elif [[ -e "${purgePath}" ]]; then
      echo "Removing existing file at ${purgePath}"
      git rm -rf "${purgePath}"
    fi

    # This deploy replaces ${purgePath} wholesale, so on a concurrent-deploy retry
    # it must be restored to exactly this content (clean last-writer-wins).
    OWNED_PURGE_PATHS+=("${purgePath}")
  fi
  # Note: when PURGE_EXISTING=false this deploy MERGES into ${PUBLISH_PATH}, so it
  # is deliberately NOT reconciled - the "-X theirs" rebase already keeps a
  # concurrent deploy's independent files while winning any file-level conflict,
  # which is exactly the intended merge semantics. Re-asserting the whole folder
  # here would wrongly revert the other deploy's changes to files we also touched.

  echo "Publishing ${SOURCE_FOLDER} to ${DOCUMENTATION_BRANCH}:${PUBLISH_PATH}"
  mkdir -p "${PUBLISH_PATH}"
  cp -r "../${SOURCE_FOLDER}/." "${PUBLISH_PATH}"
  git add --verbose "${PUBLISH_PATH}"/*
}

is_highest_version() {
  local new_folder="$1"   # e.g. "7.0.x"
  
  # Strip the trailing ".x" → "7.0", then parse into major/minor
  local new_major new_minor
  local folder_no_x="${new_folder%.x}"  # "7.0"
  IFS="." read -r new_major new_minor <<< "$folder_no_x"

  # Loop over all folders that look like 7.0.x
  for folder in [0-9]*.x; do
    [[ -d "$folder" ]] || continue  # skip if not a real folder

    # Use a regex match to parse existing folders into major/minor
    if [[ "$folder" =~ ^([0-9]+)\.([0-9]+)\.x$ ]]; then
      local existing_major="${BASH_REMATCH[1]}"
      local existing_minor="${BASH_REMATCH[2]}"

      # Compare numeric major/minor
      if (( existing_major > new_major )); then
        # Found a folder with a higher major
        return 1
      elif (( existing_major == new_major && existing_minor > new_minor )); then
        # Found a folder with same major but higher minor
        return 1
      fi
    fi
  done

  return 0
}

# Paths this deploy owns. Populated by publish_artifacts and used to reconcile the
# working tree after rebasing onto a concurrent deploy (see reconcile_owned_paths).
#   OWNED_PURGE_PATHS - folders this deploy replaces wholesale (PURGE_EXISTING=true):
#                       on retry, strip anything a concurrent deploy left there and
#                       restore exactly this deploy's content (clean last-writer-wins).
#   OWNED_KEEP_PATHS  - single files this deploy owns outright (e.g. the root
#                       index.html): on retry, re-assert just that file. Merged
#                       folders (PURGE_EXISTING=false) are intentionally NOT listed
#                       here - the "-X theirs" rebase already gives correct merge
#                       semantics, and re-asserting them would clobber a concurrent
#                       deploy's independent changes.
OWNED_PURGE_PATHS=()
OWNED_KEEP_PATHS=()

# If this release publishes the shared "latest" folder, remember its path and this
# release's generic (X.X.x) version. "latest" must always hold the HIGHEST released
# version, but is_highest_version is evaluated against the checkout taken before a
# concurrent higher release may have landed. reconcile_owned_paths re-checks these
# against the rebased tree so a lower release retried after a higher one does not
# overwrite "latest" with older docs. Empty unless this deploy published "latest".
LATEST_OWNED_PATH=""
LATEST_GENERIC_VERSION=""

# After a push is rejected we rebase this deploy's commit onto the concurrent deploy
# with "-X theirs", which wins FILE-LEVEL conflicts but cannot remove files the other
# deploy ADDED to a folder we own. Re-assert our owned paths from our original commit
# so shared folders (latest, X.X.x, ...) end up as a clean last-writer-wins instead of
# a union of both deploys' files. Everything the other deploy published independently
# (its own version folders) is untouched and preserved.
reconcile_owned_paths() {
  local p changed=false

  # "latest" must hold the HIGHEST released version. If a concurrent, higher
  # release landed first (its X.X.x folder is now in the just-rebased tree) and
  # THIS is a lower release, do not overwrite "latest" with our older docs:
  # restore it to the remote's (higher) content and drop it from our owned set.
  # Re-run is_highest_version from within TARGET_FOLDER, where the version folders
  # actually live, against the merged tree. Same-minor patches compare equal, so
  # they still fall through to last-writer-wins, which is fine.
  if [ -n "${LATEST_OWNED_PATH}" ] && [ -n "${LATEST_GENERIC_VERSION}" ]; then
    if ! ( cd "${TARGET_FOLDER}" && is_highest_version "${LATEST_GENERIC_VERSION}" ); then
      echo "A concurrent higher release now owns ${LATEST_OWNED_PATH}; yielding it to the remote."
      # Strip our rebased copy first so files that exist ONLY in our (lower)
      # version are removed - otherwise the checkout below would leave them and
      # "latest" would become a union of both releases instead of exactly the
      # remote's higher version. FETCH_HEAD is the remote tip fetched by the
      # preceding `git pull --rebase`, i.e. the concurrent deploy we rebased onto.
      git rm -rf --ignore-unmatch --quiet -- "${LATEST_OWNED_PATH}" >/dev/null 2>&1 || true
      # Restore the remote's (higher) version IF the remote actually publishes this
      # path. If it does not (e.g. the higher release skipped "latest"), the
      # `git rm` above already staged its removal - there is nothing to restore, and
      # a `git add -A` on the now-absent pathspec would exit 128 and abort. A
      # genuinely broken FETCH_HEAD makes `git ls-tree` exit nonzero, which - left
      # unmasked - fails the job under `set -e` rather than being mistaken for
      # "path absent". A checkout failure on a path the remote DOES have also aborts.
      local remote_latest
      remote_latest="$(git ls-tree -r FETCH_HEAD -- "${LATEST_OWNED_PATH}")"
      if [ -n "${remote_latest}" ]; then
        git checkout FETCH_HEAD -- "${LATEST_OWNED_PATH}"
        git add -A -- "${LATEST_OWNED_PATH}"
      fi
      local kept=() x
      for x in "${OWNED_PURGE_PATHS[@]}"; do
        [ "$x" = "${LATEST_OWNED_PATH}" ] || kept+=("$x")
      done
      OWNED_PURGE_PATHS=("${kept[@]}")
      changed=true
    fi
  fi

  # These owned paths are always present in DEPLOY_COMMIT (this deploy published
  # them), so a restore failure means a genuinely broken repo state. Do NOT mask
  # checkout/add failures with `|| true`: under `set -e` they must fail the job
  # rather than silently commit a deletion and publish broken/missing docs. Only
  # the best-effort pre-clean `git rm` (guarded by --ignore-unmatch) stays lenient.
  for p in "${OWNED_PURGE_PATHS[@]}"; do
    git rm -rf --ignore-unmatch --quiet -- "$p" >/dev/null 2>&1 || true
    git checkout "$DEPLOY_COMMIT" -- "$p"
    git add -A -- "$p"
    changed=true
  done
  for p in "${OWNED_KEEP_PATHS[@]}"; do
    git checkout "$DEPLOY_COMMIT" -- "$p"
    git add -A -- "$p"
    changed=true
  done
  if [ "$changed" = true ]; then
    echo "Re-asserted this deploy's owned paths after rebase."
    # A fresh commit (not --amend) so this is safe even when resolve_rebase_conflicts
    # had to `git rebase --skip` an emptied deploy commit, leaving HEAD at the remote
    # tip - amending there would rewrite the concurrent deploy's commit.
    git commit --quiet --allow-empty -m "Reconcile documentation paths after concurrent deploy"
  fi
}

# `git pull --rebase -X theirs` auto-resolves file-CONTENT conflicts in favour of this
# deploy, but it canNOT auto-resolve modify/delete conflicts - e.g. this deploy purged a
# file from a shared folder (latest, X.X.x, snapshot) that a concurrent deploy modified.
# Such a conflict halts the rebase mid-way; without handling it the retry loop just aborts
# and re-hits the identical conflict until it exhausts all attempts. Finish the rebase
# deterministically instead: for every still-conflicted path take THIS deploy's version
# from DEPLOY_COMMIT, or drop it if this deploy does not have it, then continue.
# reconcile_owned_paths afterwards still restores owned folders wholesale. Returns 0 if a
# rebase was in progress and was driven to completion, 1 otherwise (e.g. the pull failed
# for a non-conflict reason such as network/auth, so the caller must not proceed).
resolve_rebase_conflicts() {
  local git_dir
  git_dir="$(git rev-parse --git-dir 2>/dev/null)" || return 1
  if [ ! -d "${git_dir}/rebase-merge" ] && [ ! -d "${git_dir}/rebase-apply" ]; then
    return 1
  fi

  echo "Rebase halted on a conflict '-X theirs' cannot auto-resolve (e.g. a file this" >&2
  echo "deploy removed but a concurrent deploy modified). Resolving each conflicted path" >&2
  echo "in favour of THIS deploy and continuing the rebase." >&2

  local guard=0 unmerged f
  while [ -d "${git_dir}/rebase-merge" ] || [ -d "${git_dir}/rebase-apply" ]; do
    guard=$((guard + 1))
    if [ "${guard}" -gt 100 ]; then
      echo "Gave up resolving rebase conflicts after ${guard} iterations." >&2
      git rebase --abort >/dev/null 2>&1 || true
      return 1
    fi

    unmerged="$(git diff --name-only --diff-filter=U 2>/dev/null)"
    if [ -n "${unmerged}" ]; then
      while IFS= read -r f; do
        [ -n "${f}" ] || continue
        if git cat-file -e "${DEPLOY_COMMIT}:${f}" 2>/dev/null; then
          git checkout "${DEPLOY_COMMIT}" -- "${f}" >/dev/null 2>&1 || true
          git add -- "${f}" >/dev/null 2>&1 || true
        else
          git rm -f --ignore-unmatch --quiet -- "${f}" >/dev/null 2>&1 || true
        fi
      done <<< "${unmerged}"
    fi

    if ! GIT_EDITOR=: git rebase --continue >/dev/null 2>&1; then
      # The replayed commit may now be empty (all its changes already exist upstream).
      # Drop it; reconcile_owned_paths re-asserts this deploy's owned paths afterwards.
      if ! GIT_EDITOR=: git rebase --skip >/dev/null 2>&1; then
        git rebase --abort >/dev/null 2>&1 || true
        return 1
      fi
    fi
  done
  return 0
}

set -e

set_value_or_error "${DOCUMENTATION_BRANCH}" 'gh-pages' 'DOCUMENTATION_BRANCH'

# GH_TOKEN - the token to access the github repository, can be GITHUB_TOKEN if the same repo and permissions are set correctly
set_value_or_error "${GH_TOKEN}" "${GITHUB_TOKEN}" "GH_TOKEN"

# GITHUB_USER_NAME - the username to commit to documentation branch, defaults to GITHUB_ACTOR (assumes permissions are set correctly)
set_value_or_error "${GITHUB_USER_NAME}" "${GITHUB_ACTOR}" "GITHUB_USER_NAME"

# LAST_RELEASE_FOLDER - when a release is performed, instead of just copying it to a version number, copy it to this static folder name
set_value_or_error "${LAST_RELEASE_FOLDER}" "latest" "LAST_RELEASE_FOLDER"

# SKIP_RELEASE_FOLDER - if copying to the release folder should be skipped
set_value_or_error "${SKIP_RELEASE_FOLDER}" "false" "SKIP_RELEASE_FOLDER" "true false"

# LAST_SNAPSHOT_FOLDER - when a snapshot is performed, instead of just copying it a version number, copy it to this static folder name
set_path_value_or_error "${LAST_SNAPSHOT_FOLDER}" "snapshot" "LAST_SNAPSHOT_FOLDER"

# SKIP_SNAPSHOT_FOLDER - if copying to the snapshot folder should be skipped
set_path_value_or_error "${SKIP_SNAPSHOT_FOLDER}" "false" "SKIP_SNAPSHOT_FOLDER" "true false"

# GRADLE_PUBLISH_RELEASE - Whether the documents being published is a release or not, expects 'true', 'false' values
set_value_or_error "${GRADLE_PUBLISH_RELEASE}" "" "GRADLE_PUBLISH_RELEASE" "true false"

# TARGET_REPOSITORY - the document repository to commit to, including owner
set_value_or_error "${TARGET_REPOSITORY}" "${GITHUB_REPOSITORY}" "TARGET_REPOSITORY"
if [[ ! "$TARGET_REPOSITORY" =~ ^[^/]+/[^/]+$ ]]; then
  echo "ERROR: TARGET_REPOSITORY must be in the format 'owner/repo'" >&2
  exit 1
fi

# SOURCE_FOLDER - the relative path of the source documentation folder from the root of the repo
set_path_value_or_error "${SOURCE_FOLDER}" "" "SOURCE_FOLDER"

# TARGET_FOLDER - the base folder to publish documentation to
set_path_value_or_error "${TARGET_FOLDER}" "." "TARGET_FOLDER"
mkdir -p "${TARGET_FOLDER}"

# TARGET_SUBFOLDER - an optional sub folder to publish to
set_path_value_or_error "${TARGET_SUBFOLDER}" "." "TARGET_SUBFOLDER"
if [ "${TARGET_SUBFOLDER}" == "." ]; then
  unset TARGET_SUBFOLDER;
fi

# PURGE_EXISTING - whether to remove the files before upload
set_value_or_error "${PURGE_EXISTING}" "true" "PURGE_EXISTING" "true false"

# PURGE_BY_BASE_PATH - sometimes it's useful to purge the base version folder, instead of the targeted nested sub folder
set_value_or_error "${PURGE_BY_BASE_PATH}" "false" "PURGE_BY_BASE_PATH" "true false"

# GITHUB_WORKSPACE - the safe directory to checkout to
set_value_or_error "${GITHUB_WORKSPACE}" "" "GITHUB_WORKSPACE"

if [[ "$SKIP_SNAPSHOT_FOLDER" == "true" && "$GRADLE_PUBLISH_RELEASE" == "false" ]]; then
  echo "Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment."
  exit 0
fi

set_value_or_error "${GITHUB_URL_BASE}" "github.com" "GITHUB_URL_BASE"
set_value_or_error "${GIT_TRANSFER_PROTOCOL}" "https" "GIT_TRANSFER_PROTOCOL"
GIT_REPO_URL="${GIT_TRANSFER_PROTOCOL}://${GITHUB_USER_NAME}:${GH_TOKEN}@${GITHUB_URL_BASE}/${TARGET_REPOSITORY}.git"

# Initialize a Git Repository under a separate location from the existing checkout that will be the documentation branch
cd "${GITHUB_WORKSPACE}"
git init
git config --global user.email "${GITHUB_USER_NAME}@users.noreply.github.com"
git config --global user.name "${GITHUB_USER_NAME}"
git config --global http.version HTTP/1.1
git config --global http.postBuffer 157286400

# Create or checkout the documentation branch
if git ls-remote --heads "${GIT_REPO_URL}" "${DOCUMENTATION_BRANCH}" | grep -q "refs/heads/${DOCUMENTATION_BRANCH}"; then
  echo "::group::Checkout documentation branch"
  echo "documentation branch found, cloning"
  git clone "${GIT_REPO_URL}" "${DOCUMENTATION_BRANCH}" --branch "${DOCUMENTATION_BRANCH}" --single-branch --depth 1
  cd ${DOCUMENTATION_BRANCH}
  echo "::endgroup::"
else
  echo "::group::Creating documentation branch"
  echo "Creating documentation branch ${DOCUMENTATION_BRANCH} as it does not exist"
  mkdir "${DOCUMENTATION_BRANCH}"
  cd "${DOCUMENTATION_BRANCH}"
  git init
  git checkout -b "${DOCUMENTATION_BRANCH}"
  git remote add origin "${GIT_REPO_URL}"
  echo "::endgroup::"
fi

# grails repos have a convention that they create a ghpages.html to replace the root index.html
if [[ -f "../${SOURCE_FOLDER}/ghpages.html" ]]; then
  echo "::group::Staging root index.html"
  echo "${SOURCE_FOLDER}/ghpages.html detected, replacing root index.html"
  cp "../${SOURCE_FOLDER}/ghpages.html" index.html
  git add index.html
  OWNED_KEEP_PATHS+=("index.html")
  echo "::endgroup::"
fi

# stage the documents
if [[ "$GRADLE_PUBLISH_RELEASE" == "false" ]]; then
  echo "::group::Publishing Snapshot"
  echo "Snapshot detected"

  # Subfolder support
  BASE_PUBLISH_PATH="${TARGET_FOLDER}/${LAST_SNAPSHOT_FOLDER}"
  if [ -n "${TARGET_SUBFOLDER}" ]; then
    PUBLISH_PATH="${TARGET_FOLDER}/${LAST_SNAPSHOT_FOLDER}/${TARGET_SUBFOLDER}"
  else
    PUBLISH_PATH="${TARGET_FOLDER}/${LAST_SNAPSHOT_FOLDER}"
  fi

  publish_artifacts
  echo "::endgroup::"
else
  echo "Release detected"

  # RELEASE_TAG_PREFIX - the tag prefix to use for a release version, defaults to 'v'
  set_value_or_error "${RELEASE_TAG_PREFIX}" "v" "RELEASE_TAG_PREFIX"

  # VERSION - the version number of this snapshot or release, v7.0.2 will be `7.0.2`, 7.0.x will be 7.0.x
  set_value_or_error "${VERSION}" "${GITHUB_REF_NAME}" "VERSION"
  if [[ ! "${VERSION}" =~ ^(${RELEASE_TAG_PREFIX})?[^.]+\.[^.]+\.[^.]+$ ]]; then
    echo "ERROR: VERSION must be in the format 'X.X.X' or '${RELEASE_TAG_PREFIX}X.X.X'. Got: '${VERSION}'"
    exit 1
  fi
  if [[ $VERSION == "${RELEASE_TAG_PREFIX}"* ]]; then
    VERSION=${VERSION:${#RELEASE_TAG_PREFIX}}
  fi

  # Publish to the specific version folder
  echo "::group::Publishing Specific Release Version: ${VERSION}"
  BASE_PUBLISH_PATH="${TARGET_FOLDER}/${VERSION}"
  if [ -n "${TARGET_SUBFOLDER}" ]; then
    PUBLISH_PATH="${TARGET_FOLDER}/${VERSION}/${TARGET_SUBFOLDER}"
  else
    PUBLISH_PATH="${TARGET_FOLDER}/${VERSION}"
  fi
  publish_artifacts
  echo "Published release documentation to ${PUBLISH_PATH}"
  echo "::endgroup::"

  # Publish to the generic version folder
  genericVersionFolder="${VERSION%.*}"
  genericVersionFolder="${genericVersionFolder}.x"
  echo "::group::Publishing Generic Release Version: ${genericVersionFolder}"
  BASE_PUBLISH_PATH="${TARGET_FOLDER}/${genericVersionFolder}"
  if [ -n "${TARGET_SUBFOLDER}" ]; then
    PUBLISH_PATH="${TARGET_FOLDER}/${genericVersionFolder}/${TARGET_SUBFOLDER}"
  else
    PUBLISH_PATH="${TARGET_FOLDER}/${genericVersionFolder}"
  fi
  publish_artifacts
  echo "Published release documentation to ${genericVersionFolder}"
  echo "::endgroup::"

  # Publish to the latest release folder if needed 
  if [[ "$SKIP_RELEASE_FOLDER" == "false" ]]; then
    if is_highest_version "${genericVersionFolder}"; then
      echo "::group::Overwriting ${LAST_RELEASE_FOLDER} with the latest release documentation"
      BASE_PUBLISH_PATH="${TARGET_FOLDER}/${LAST_RELEASE_FOLDER}"
      if [ -n "${TARGET_SUBFOLDER}" ]; then
        PUBLISH_PATH="${TARGET_FOLDER}/${LAST_RELEASE_FOLDER}/${TARGET_SUBFOLDER}"
      else
        PUBLISH_PATH="${TARGET_FOLDER}/${LAST_RELEASE_FOLDER}"
      fi
      publish_artifacts
      echo "Published a copy of documentation to ${PUBLISH_PATH}"
      # Record the "latest" folder so reconcile_owned_paths can re-check, after a
      # concurrent-deploy rebase, whether this release is still the highest. Even
      # merge-mode deployments need this check: a lower release replayed after a
      # concurrent higher release must yield "latest" back to the higher release.
      LATEST_GENERIC_VERSION="${genericVersionFolder}"
      if [[ "${PURGE_EXISTING}" == "true" && "${PURGE_BY_BASE_PATH}" == "true" ]]; then
        LATEST_OWNED_PATH="${BASE_PUBLISH_PATH}"
      else
        LATEST_OWNED_PATH="${PUBLISH_PATH}"
      fi
      echo "::endgroup::"
    else
      echo "::group::Skipped Latest Release Documentation - not highest"
      echo "Skipping documentation copy to '${LAST_RELEASE_FOLDER}' because ${genericVersionFolder} is NOT the highest."
      echo "::endgroup::"
    fi
  else
    echo "::group::Skipped Latest Release Documentation"
    echo "Skipping documentation copy to ${LAST_RELEASE_FOLDER}"
    echo "::endgroup::"
  fi
fi

echo "::group::Committing Changes"
echo "Detected the following delta for commit:"
git status

echo "Committing changes."
git commit -m "Deploying to documentation branch - $(date +"%T")" --quiet --allow-empty

# Remember this deploy's own commit. After a rebase rewrites it on top of a
# concurrent deploy, reconcile_owned_paths restores our owned folders from here.
DEPLOY_COMMIT="$(git rev-parse HEAD)"

MAX_PUSH_ATTEMPTS=5
PUSH_ATTEMPT=1
while [ $PUSH_ATTEMPT -le $MAX_PUSH_ATTEMPTS ]; do
  echo "Push attempt ${PUSH_ATTEMPT}/${MAX_PUSH_ATTEMPTS}"
  if git push "${GIT_REPO_URL}" "${DOCUMENTATION_BRANCH}"; then
    echo "Deployment successful!"
    break
  fi
  if [ $PUSH_ATTEMPT -eq $MAX_PUSH_ATTEMPTS ]; then
    echo "ERROR: Push failed after ${MAX_PUSH_ATTEMPTS} attempts." >&2
    exit 1
  fi

  # The push was rejected because a concurrent deploy (e.g. another release
  # publishing at the same time) advanced the documentation branch. Replay this
  # deploy's commit on top of the remote so both survive.
  #
  # Specific-version folders (the exact X.X.X) never collide across different
  # releases; the paths that DO overlap are the shared ones - "latest", the
  # generic "X.X.x" (every patch of a minor writes it), index.html, and the
  # snapshot folder. Rebase with "-X theirs" to auto-resolve those file-level
  # conflicts in favour of THIS deploy (during a rebase, "theirs" is the commit
  # being replayed). Without this the rebase stops on a merge conflict and,
  # under `set -e`, kills the whole job - which is why concurrent releases fail
  # today. "-X theirs" wins conflicting files but cannot remove files the other
  # deploy ADDED to a folder we own, so reconcile_owned_paths (below) then
  # re-asserts our owned folders to a clean last-writer-wins.
  echo "Push rejected, rebasing onto remote changes and retrying..."
  PUSH_ATTEMPT=$((PUSH_ATTEMPT + 1))
  BACKOFF=$(( (PUSH_ATTEMPT * 2) + (RANDOM % 3) ))
  echo "Waiting ${BACKOFF}s before retry..."
  sleep $BACKOFF
  if git pull --rebase -X theirs "${GIT_REPO_URL}" "${DOCUMENTATION_BRANCH}"; then
    reconcile_owned_paths
  elif resolve_rebase_conflicts; then
    reconcile_owned_paths
  else
    echo "Rebase could not be completed automatically; aborting before the next attempt." >&2
    git rebase --abort >/dev/null 2>&1 || true
  fi
done
echo "::endgroup::"
