<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# `cascade-merge` Action

## Purpose

Automatically merges a source branch into the next downstream branch from a caller-provided ordered list of branches.

This action does not create pull requests. It pushes merge commits directly to downstream branches.

It is intended to be generic and reusable across repositories that maintain multiple long-lived branches.

## Requirements

1. Requires `contents: write` so the action can push merged branches.
2. The repository checkout must retain credentials for pushing to `origin`.

## Inputs

* `branch-order` - required comma-separated or newline-separated ordered list of branches. Example: `7.0.x,7.1.x,8.0.x`
* `source-branch` - optional source branch. If omitted, the action uses the current branch ref.

## Behavior

1. Finds `source-branch` in `branch-order`.
2. Merges it into the next branch in the list.
3. Attempts only the next adjacent downstream merge for the current branch.
4. Pushes the target branch when the merge succeeds without conflicts.
5. Exits with an error if that merge conflicts.
6. If the current branch is not listed in `branch-order`, the action exits successfully without doing anything.
7. If the source-only commit set contains a commit message with `[skip merge]`, the action exits with an error and requires a manual merge.

For example, with `7.0.x,7.1.x,8.0.x`:

1. The action attempts `7.0.x -> 7.1.x`.
2. When the workflow file is merged into `7.1.x`, a later run there can then attempt `7.1.x -> 8.0.x`.
3. Each branch only needs to know the full ordering; the action derives the next merge target from the current branch.

## Example Usage

This action can be defined once per maintained branch and then merged forward with the branch itself.

For example, the same workflow file can exist in `7.0.x`, `7.1.x`, and `8.0.x` with the same `branch-order`. When it runs on `7.0.x`, it attempts only `7.0.x -> 7.1.x`. After that workflow file is merged into `7.1.x`, the same definition runs there and attempts `7.1.x -> 8.0.x`. No workflow edits are required per branch as long as the branch names remain in the ordered list.

```yaml
name: "Cascade Merge: next release branch"

on:
  workflow_dispatch:
  push:
    branches:
      - 7.0.x
      - 7.1.x
      - 8.0.x

jobs:
  cascade-merge:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: "🔀 Merge current branch into next downstream branch"
        uses: apache/grails-github-actions/cascade-merge@asf
        with:
          branch-order: |
            7.0.x
            7.1.x
            8.0.x
```

Suggested naming so the workflow intent is obvious in GitHub Actions:

* Workflow name: `Cascade Merge: next release branch`
* Step name: `Merge current branch into next downstream branch`

How this reads in practice:

* On `7.0.x`, the workflow effectively means `7.0.x -> 7.1.x`
* On `7.1.x`, the same workflow effectively means `7.1.x -> 8.0.x`
* On `8.0.x`, there is no downstream branch, so the action exits without merging anything
* On any branch not present in `branch-order`, the action exits without merging anything

To block an automatic merge for a specific change, include `[skip merge]` in the commit message. If any commit that would be merged into the downstream branch contains that marker, the action exits with an error and prints that a manual merge is required.

Example commit message:

```text
docs: update release notes formatting [skip merge]
```

That commit remains on the source branch, but `cascade-merge` will stop before merging it forward automatically.

Equivalent compact input:

```yaml
      - name: "🔀 Merge current branch into next downstream branch"
        uses: apache/grails-github-actions/cascade-merge@asf
        with:
          branch-order: 7.0.x,7.1.x,8.0.x
```

Explicitly setting the source branch:

```yaml
      - name: "🔀 Cascade merge from 7.0.x"
        uses: apache/grails-github-actions/cascade-merge@asf
        with:
          source-branch: 7.0.x
          branch-order: 7.0.x,7.1.x,8.0.x
```
