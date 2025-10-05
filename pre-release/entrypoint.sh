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
#   contents: write // tags / commits

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

  # Export the variable so all variables are available in third party scripts
  eval "export $variableName=\"\$decidedValue\""
}

set -e

echo "::group::Setup"
set_value_or_error "${GITHUB_TOKEN}" "" "GITHUB_TOKEN"
set_value_or_error "${RELEASE_VERSION}" "${GITHUB_REF:10}" "RELEASE_VERSION"
set_value_or_error "${RELEASE_TAG_PREFIX}" "v" "RELEASE_TAG_PREFIX"

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

set_value_or_error "${RELEASE_URL}" `cat $GITHUB_EVENT_PATH | jq '.release.url' | sed -e 's/^"\(.*\)"$/\1/g'` "RELEASE_URL"
set_value_or_error "${GIT_USER_NAME}" "${GITHUB_ACTOR}" "GIT_USER_NAME"
set_value_or_error "${GITHUB_WORKSPACE}" "." "GIT_SAFE_DIR"
set_value_or_error "${PROPERTY_FILE_NAME}" "gradle.properties" "PROPERTY_FILE_NAME"

echo "Configuring git"
git config --global --add safe.directory "${GIT_SAFE_DIR}"
git config --global user.email "${GIT_USER_NAME}@users.noreply.github.com"
git config --global user.name "${GIT_USER_NAME}"
git fetch
echo "::endgroup::"

echo "::group::Updating Project Version"
git checkout "${RELEASE_TAG_PREFIX}${RELEASE_VERSION}"
echo "Setting release version in ${PROPERTY_FILE_NAME}"
sed -i "s/^projectVersion\=.*$/projectVersion\=${RELEASE_VERSION}/" "${PROPERTY_FILE_NAME}"
sed -i "s/^version\=.*$/version\=${RELEASE_VERSION}/" "${PROPERTY_FILE_NAME}"
cat "${PROPERTY_FILE_NAME}"
echo "\n"
git add "${PROPERTY_FILE_NAME}"
echo "::endgroup::"

if [[ -n "${RELEASE_SCRIPT_PATH}" && -x "${GITHUB_WORKSPACE}/${RELEASE_SCRIPT_PATH}" ]]; then
  echo "::group::Applying Release Script"
  echo "Executing additional release script at ${GITHUB_WORKSPACE}/${RELEASE_SCRIPT_PATH}"
  "${GITHUB_WORKSPACE}/${RELEASE_SCRIPT_PATH}"
  echo "::endgroup::"
else
  if [[ -n "${RELEASE_SCRIPT_PATH}" ]]; then
    echo "ERROR: RELEASE_SCRIPT_PATH is set to '${RELEASE_SCRIPT_PATH}' but is not executable or does not exist." >&2
    exit 1
  fi
fi

echo "::group::Pushing Project Changes"
echo "Pushing release version and recreating ${RELEASE_TAG_PREFIX}${RELEASE_VERSION} tag"
if ! git diff --quiet || ! git diff --cached --quiet; then
  git commit -m "[skip ci] Release ${RELEASE_TAG_PREFIX}${RELEASE_VERSION}"
else
  echo "No changes to commit - was the release version already set?"
fi

git tag -fa ${RELEASE_TAG_PREFIX}${RELEASE_VERSION} -m "Release ${RELEASE_TAG_PREFIX}${RELEASE_VERSION}"
# force push the updated tag
git push origin "${RELEASE_TAG_PREFIX}${RELEASE_VERSION}" --force
echo "::endgroup::"

echo "::group::Updating Release for Project Changes"
echo "Closing the release using ${RELEASE_URL} after updating the tag:"
curl --request PATCH -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Content-Type: application/json" "${RELEASE_URL}" --data "{\"draft\": false}"
printf "\n"
echo "Pre Release steps complete"
echo "::endgroup::"
