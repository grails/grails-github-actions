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
# `pre-release` Action

## Purpose: Handles Tag updates to ensure the Release Version is set

A GitHub action that handles any pre-release steps as part of a GitHub release process.  This includes: 

1. Updating the `projectVersion` or `version` in `gradle.properties` to match the tag version.
2. Optionally running an additional script as part of the `projectVersion` change to transform files in the repository.
3. Commiting changes from 1 & 2 above and force pushing an update to the tag.
4. Moving the release back from `draft` to `published` because of the tag update.

Please note that this action allows users to simply create a GitHub release and this action handles matching the version of the GitHub release to the checked in version.

## Requirements

1. Requires the permission `contents: write` to update the tag & release.

## Environment Variables
* (required) `RELEASE_VERSION` - The version of the release being created.
* (optional) `RELEASE_TAG_PREFIX` - The prefix of the release tag. If not set, it will default to `v` (e.g., `v1.0.0`).
* (optional) `PROPERTY_FILE_NAME` - defaults to `gradle.properties`, the property file containing the version property to update
* (optional) `RELEASE_SCRIPT_PATH` - An optional path to a custom shell script that will be executed after the version replacement in the property file defined by `PROPERTY_FILE_NAME`, but prior to commiting the project changes.

## Example Usage

Basic Usage:
```yaml
      - name: '⚙️ Run pre-release'
        uses: apache/grails-github-actions/pre-release@asf
        env:
          RELEASE_VERSION: ${{ steps.release_version.outputs.value }}
```

Running a custom script `myScript.sh` that's checked in under `.github/scripts`:
```yaml
      - name: '⚙️ Run pre-release'
        uses: apache/grails-github-actions/pre-release@asf
        env:
          RELEASE_VERSION: ${{ steps.release_version.outputs.value }}
          RELEASE_SCRIPT_PATH: '.github/scripts/myScript.sh'
```