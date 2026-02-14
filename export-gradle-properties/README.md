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

# `export-gradle-properties` Action

## Purpose: Export Gradle Properties as Environment Variables

A GitHub Action that takes a Java properties file (typically `gradle.properties`) and exports each property as an environment variable for use in subsequent steps of your workflow.

## Inputs

- `file` (required): Location of the properties file (default: `gradle.properties`)
- `prefix` (optional): Prefix to add to environment variable names. If provided without a trailing underscore, one will be added automatically (e.g., `FOO` becomes `FOO_`)

## Example usage

### Basic usage without prefix

```yaml
- name: "Export Gradle Properties"
  uses: apache/grails-github-actions/export-gradle-properties@asf
- name: "Use the property"
  run:
    echo "${PROJECT_VERSION}"
  env:
    PROJECT_VERSION: ${{ env.projectVersion }}
```

### Usage with prefix

```yaml
- name: "Export Gradle Properties with Prefix"
  uses: apache/grails-github-actions/export-gradle-properties@asf
  with:
    prefix: 'FOO'
- name: "Use the prefixed property"
  run:
    echo "${JAVA_VERSION}"
  env:
    JAVA_VERSION: ${{ env.FOO_javaVersion }}
```

