/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.grails.github.mocks.cli

class GitHubCliMock implements CliMock {
    @Override
    String getFileName() {
        'gh'
    }

    @Override
    String getFileContents() {
        """
#!/bin/sh
# gh — GitHub CLI mock for testing grails-github-actions
set -eu

cmd="\${1:-}"; shift || true
case "\$cmd" in
  pr)
    sub="\${1:-}"; shift || true
    case "\$sub" in
      create)
        title=""; body=""; base=""; head=""; fill=false
        while [ "\$#" -gt 0 ]; do
          case "\$1" in
            --title) title="\$2"; shift 2 ;;
            --body)  body="\$2"; shift 2 ;;
            --base)  base="\$2"; shift 2 ;;
            --head)  head="\$2"; shift 2 ;;
            --fill)  fill=true; shift ;;
            --repo)  shift 2 ;;              # ignored
            *)       shift ;;                # ignore unknowns for resilience
          esac
        done

        # Behavior control:
        #  GH_MOCK_PR_CREATE = create|exists|fail  (default: create)
        mode="\${GH_MOCK_PR_CREATE:-create}"

        case "\$mode" in
          create)
            printf "%s\n" "https://github.com/mock-org/mock-repo/pull/42"
            exit 0
            ;;
          exists)
            echo "GraphQL: pull request already exists for head '\$head' into base '\$base'" >&2
            exit 1
            ;;
          fail)
            echo "gh-mock: simulated failure creating PR" >&2
            exit 2
            ;;
          *)
            echo "gh-mock: invalid GH_MOCK_PR_CREATE='\$mode'" >&2
            exit 3
            ;;
        esac
        ;;
      view)
        # Accept: gh pr view <branch> --web
        mode="\${GH_MOCK_PR_VIEW:-success}"
        if [ "\$mode" = "fail" ]; then
          echo "gh-mock: simulated failure viewing PR" >&2
          exit 1
        fi
        printf "%s\n" "https://github.com/mock-org/mock-repo/pull/42"
        exit 0
        ;;
      edit)
        ref="\${1:-}"; shift || true
        labels=""
        while [ "\$#" -gt 0 ]; do
          case "\$1" in
            --add-label)
              if [ -n "\$labels" ]; then
                labels="\$labels,\$2"
              else
                labels="\$2"
              fi
              shift 2
              ;;
            *)
              shift
              ;;
          esac
        done
        if [ "\${GH_MOCK_PR_EDIT:-success}" = "invalid-label" ]; then
          echo "could not add label: label not found" >&2
          exit 1
        fi
        printf 'ref=%s labels=%s\n' "\$ref" "\$labels"
        exit 0
        ;;
      *)
        echo "gh-mock: unsupported subcommand 'pr \$sub'" >&2
        exit 2
        ;;
    esac
    ;;
  *)
    echo "gh-mock: unsupported command '\$cmd'" >&2
    exit 2
    ;;
esac
        """
    }
}
