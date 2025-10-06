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

# The following permissions are required to successfully execute this script:
# permissions:
#    issues: write # to close milestone (script will silently fail if not set)
#    contents: write # to commit
#    pull-requests: write # to create merge PR
#
# This script expects the github action to have checked out the repository with fetch-depth: 0

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

  eval "export $variableName=\"\$decidedValue\""
}

set -e

set_value_or_error "${GIT_USER_NAME}" "${GITHUB_ACTOR}" "GIT_USER_NAME"
set_value_or_error "${GITHUB_WORKSPACE}" "." "GIT_SAFE_DIR"
# for testing: git remote get-url origin | sed -E 's#(git@|https://)github.com[:/](.*).git#\2#'
set_value_or_error "${GITHUB_REPOSITORY}" "" "GITHUB_REPOSITORY"
set_value_or_error "${GITHUB_TOKEN}" "" "GITHUB_TOKEN"
set_value_or_error "${GITHUB_EVENT_PATH}" "" "GITHUB_EVENT_PATH"
set_value_or_error "${GITHUB_API_URL}" "" "GITHUB_API_URL"
set_value_or_error "${RELEASE_VERSION}" "${GITHUB_REF#refs/*/}" "RELEASE_VERSION"
set_value_or_error "${RELEASE_TAG_PREFIX}" "v" "RELEASE_TAG_PREFIX"
set_value_or_error "${PROPERTY_FILE_NAME}" "gradle.properties" "PROPERTY_FILE_NAME"

echo "::group::Determine release version"
if [[ ! "${RELEASE_VERSION}" =~ ^(${RELEASE_TAG_PREFIX})?[^.]+\.[^.]+\.[^.]+$ ]]; then
  echo "ERROR: RELEASE_VERSION must be in the format 'X.X.X' or '${RELEASE_TAG_PREFIX}X.X.X'. Got: '${RELEASE_VERSION}'"
  exit 1
fi
if [[ $RELEASE_VERSION == "${RELEASE_TAG_PREFIX}"* ]]; then
  RELEASE_VERSION=${RELEASE_VERSION:${#RELEASE_TAG_PREFIX}}
else
  RELEASE_VERSION="${RELEASE_VERSION}"
fi
echo "Release Version: ${RELEASE_VERSION}"
echo "::endgroup::"

echo "::group::Close Milestone (if it exists)"
set +e
echo -n "Retrieving current milestone number: "
milestone_number=`curl -s ${GITHUB_API_URL}/repos/${GITHUB_REPOSITORY}/milestones | jq -c ".[] | select (.title == \"${RELEASE_VERSION}\") | .number" | sed -e 's/"//g'`
echo "Found Milestone Id: $milestone_number"
echo "Closing current milestone"
curl -s --request PATCH -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Content-Type: application/json" ${GITHUB_API_URL}/repos/${GITHUB_REPOSITORY}/milestones/$milestone_number --data '{"state":"closed"}'
set -e
echo "::endgroup::"

echo "::group::Determine next version"
echo -n "Next Version: "
export NEXT_VERSION=`/increment_version.sh -p ${RELEASE_VERSION}`
echo "${NEXT_VERSION}"
echo "NEXT_VERSION=${NEXT_VERSION}" >> $GITHUB_OUTPUT
echo "::endgroup::"

echo "::group::Configure Git"
git config --global --add safe.directory "${GIT_SAFE_DIR}"
git config --global user.email "${GIT_USER_NAME}@users.noreply.github.com"
git config --global user.name "${GIT_USER_NAME}"
git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"
git fetch
echo "::endgroup::"

echo "::group::Determine target merge branch"
set_value_or_error "${TARGET_BRANCH}" "$(jq -r 'if has("release") and .release.target_commitish != null then .release.target_commitish else "" end' "$GITHUB_EVENT_PATH")" "TARGET_BRANCH"
echo "Target Branch is ${TARGET_BRANCH}"
TARGET_BRANCH="${TARGET_BRANCH#refs/*/}"
echo "Pruned Target Branch is ${TARGET_BRANCH}"
git checkout "${TARGET_BRANCH}"
echo "::endgroup::"

echo "::group::Creating Merge Branch from ${RELEASE_TAG_PREFIX}${RELEASE_VERSION}"
MERGE_BRANCH_NAME="merge-back-${RELEASE_VERSION}"
git checkout -b "${MERGE_BRANCH_NAME}" "${RELEASE_TAG_PREFIX}${RELEASE_VERSION}"
echo "::endgroup::"

echo "::group::Add commit to update to next version"
echo "Setting new snapshot version"
sed -i "s/^projectVersion\=.*$/projectVersion\=${NEXT_VERSION}-SNAPSHOT/" "${PROPERTY_FILE_NAME}"
sed -i "s/^version\=.*$/version\=${NEXT_VERSION}-SNAPSHOT/" "${PROPERTY_FILE_NAME}"
cat "${PROPERTY_FILE_NAME}"
printf "\n"
git add "${PROPERTY_FILE_NAME}"

if [[ -n "${RELEASE_SCRIPT_PATH}" && -x "${GITHUB_WORKSPACE}/${RELEASE_SCRIPT_PATH}" ]]; then
  echo "Executing additional release script at ${GITHUB_WORKSPACE}/${RELEASE_SCRIPT_PATH}"
  "${GITHUB_WORKSPACE}/${RELEASE_SCRIPT_PATH}"
else
  if [[ -n "${RELEASE_SCRIPT_PATH}" ]]; then
    echo "ERROR: RELEASE_SCRIPT_PATH is set to '${RELEASE_SCRIPT_PATH}' but is not executable or does not exist." >&2
    exit 1
  fi
fi

if git diff-index --quiet HEAD --; then
  git checkout --
  echo "::notice:: No version change – nothing to commit, skipping commit."
else
	echo "Committing next version ${NEXT_VERSION}-SNAPSHOT"
  git commit -m "[skip ci] Bump version to ${NEXT_VERSION}-SNAPSHOT"
fi
echo "::endgroup::"

echo "::group::Pushing Merge Branch ${MERGE_BRANCH_NAME}"
echo "Pushing changes to side branch: ${MERGE_BRANCH_NAME}"
git push --force origin "${MERGE_BRANCH_NAME}"
echo "::endgroup::"

echo "::group::Open/Reuse pull request"
PR_TITLE="chore: merge ${RELEASE_VERSION}->${TARGET_BRANCH}; bump to ${NEXT_VERSION}-SNAPSHOT"
PR_BODY="Automated snapshot bump after completing release ${RELEASE_VERSION}"
if ! gh pr create \
        --title "${PR_TITLE}" \
        --body  "${PR_BODY}" \
        --base  "${TARGET_BRANCH}" \
        --head  "${MERGE_BRANCH_NAME}" \
        --fill  >/tmp/pr-url 2>/tmp/pr-err; then
    echo "PR likely exists – existing PR:" >&2
    cat /tmp/pr-err
    gh pr view "${MERGE_BRANCH_NAME}" --web || true
else
    cat /tmp/pr-url
fi
echo "::endgroup::"