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

# `post-release` Action

## Purpose: Handles Repository Setup for the next Release Version

A GitHub Action that handles steps necessary to close out a GitHub Release process.  This includes: 

1. Creating a branch of named like `merge-back-TAGNAME` that will:
    * Include the tag changes to prevent orphaned changes.
    * Include changing the version in `gradle.properties` or `version` to the next version. 
2. Optionally closing the current milestone associated with the release.
3. Optionally running an additional script as part of the close process to transform files in the repository.
4. Removing the `prerelease` flag from the release & setting the release to the latest GitHub release.

Please note that the next version is derived from the provided `RELEASE_VERSION` using a script that assumes a [Semantic Version](https://semver.org/).

## Requirements

1. Github Actions must be allowed to create pull requests in the repository. You can configure this in the repository settings under "Actions" -> "General" -> "Workflow permissions".
2. Requires the permission `contents: write` to create a branch, commit changes to the repository, update the GitHub release to no longer be a pre-release, and update the GitHub release to be the latest.
3. Optionally requires the permission `pull-requests: write` to open the pull request to merge back changes from the tag. If this permission is not set, a Pull Request will not be created.
4. Optionally requires the permission `issues: write` if milestone closing is required.

## Environment Variables
* (optional) `RELEASE_VERSION` - The version of the release being closed. If not set, it will be derived from the `GITHUB_REF`, which as part of a release will be the tag name.
* (optional) `RELEASE_TAG_PREFIX` - The prefix of the release tag. If not set, it will default to `v` (e.g., `v1.0.0`).
* (optional) `RELEASE_SCRIPT_PATH` - An optional path to a custom shell script that will be executed after the version replacement in `gradle.properties`, but prior to commiting the project changes.

## Example Usage

Basic Usage:
```yaml
      - name: "⚙️ Run post-release"
        uses: apache/grails-github-actions/post-release@asf
```

Running a custom script `myScript.sh` that's checked in under `.github/scripts`:
```yaml
      - name: "⚙️ Run post-release"
        uses: apache/grails-github-actions/post-release@asf
        env:
          RELEASE_SCRIPT_PATH: '.github/scripts/myScript.sh'
```