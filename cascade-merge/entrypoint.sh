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

set -euo pipefail

set_value_or_error() {
  local value="$1"
  local default_value="$2"
  local variable_name="$3"

  if [[ -n "$value" ]]; then
    decided_value="$value"
  elif [[ -n "$default_value" ]]; then
    echo "${variable_name}: Using default value: ${default_value}"
    decided_value="$default_value"
  else
    echo "ERROR: A value for ${variable_name} is required." >&2
    exit 1
  fi

  eval "export ${variable_name}=\"\$decided_value\""
}

echo "::group::Setup"
set_value_or_error "${GITHUB_WORKSPACE:-}" "." "GIT_SAFE_DIR"
set_value_or_error "${GIT_USER_NAME:-}" "${GITHUB_ACTOR:-github-actions}" "GIT_USER_NAME"
set_value_or_error "${SOURCE_BRANCH:-}" "${GITHUB_REF#refs/heads/}" "SOURCE_BRANCH"
set_value_or_error "${BRANCH_ORDER:-}" "" "BRANCH_ORDER"

git config --global --add safe.directory "${GIT_SAFE_DIR}"
git config --global user.email "${GIT_USER_NAME}@users.noreply.github.com"
git config --global user.name "${GIT_USER_NAME}"
git fetch origin --prune
echo "Source Branch: ${SOURCE_BRANCH}"
echo "Branch Order: ${BRANCH_ORDER}"
echo "::endgroup::"

normalized_branch_order="$(printf '%s\n' "${BRANCH_ORDER}" | tr ',\n' '  ')"
read -r -a branch_sequence <<< "${normalized_branch_order}"

if [[ ${#branch_sequence[@]} -eq 0 ]]; then
  echo "ERROR: BRANCH_ORDER did not contain any branch names." >&2
  exit 1
fi

source_index=-1
for i in "${!branch_sequence[@]}"; do
  if [[ "${branch_sequence[$i]}" == "${SOURCE_BRANCH}" ]]; then
    source_index=$i
    break
  fi
done

if [[ ${source_index} -lt 0 ]]; then
  echo "SOURCE_BRANCH '${SOURCE_BRANCH}' was not found in BRANCH_ORDER. Nothing to merge."
  exit 0
fi

if [[ ${source_index} -eq $((${#branch_sequence[@]} - 1)) ]]; then
  echo "No downstream branches found after ${SOURCE_BRANCH}. Nothing to merge."
  exit 0
fi

source_branch="${branch_sequence[$source_index]}"
target_branch="${branch_sequence[$((source_index + 1))]}"

if git rev-parse --verify --quiet "${source_branch}" >/dev/null; then
  source_ref="${source_branch}"
else
  source_ref="origin/${source_branch}"
fi

echo "::group::Merge ${source_branch} into ${target_branch}"
git checkout -B "${target_branch}" "origin/${target_branch}"

skip_merge_commits="$(git log --format='%H %s' "origin/${target_branch}..${source_ref}" | grep '\[skip merge\]' || true)"
if [[ -n "${skip_merge_commits}" ]]; then
  echo "Commits marked with [skip merge] were found between ${source_branch} and ${target_branch}:" >&2
  printf '%s\n' "${skip_merge_commits}" >&2
  echo "ERROR: Manual merge required for ${source_branch} -> ${target_branch}." >&2
  echo "::endgroup::"
  exit 1
fi

if git merge --no-ff --no-edit -m "[skip ci] Merge ${source_branch} into ${target_branch}" "${source_ref}"; then
  if git diff --quiet "origin/${target_branch}" HEAD; then
    echo "${target_branch} is already up to date with ${source_branch}."
  else
    git push origin "${target_branch}"
    echo "Merged ${source_branch} into ${target_branch}."
  fi
else
  git merge --abort || true
  echo "ERROR: Merge conflict while merging ${source_branch} into ${target_branch}. Resolve manually." >&2
  echo "::endgroup::"
  exit 1
fi

echo "::endgroup::"
