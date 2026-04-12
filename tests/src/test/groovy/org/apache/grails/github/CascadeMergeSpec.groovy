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
package org.apache.grails.github

import org.apache.grails.github.mocks.GitHubDockerAction
import org.apache.grails.github.mocks.GitHubRepoMock
import org.apache.grails.github.mocks.GitHubVersion
import org.testcontainers.containers.Network
import org.testcontainers.containers.ContainerLaunchException
import spock.lang.Specification

class CascadeMergeSpec extends Specification {

    def 'success - merges source branch into next downstream branch'() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0', tagName: null, targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('cascade-merge', release)

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, ['7.0.x', '7.1.x', '8.0.x'])
        gitRepo.storeFiles(['README.md': '# merged from 7.0.x\n'], '7.0.x')
        gitRepo.stageRepositoryForAction('7.0.x', false)

        and:
        def env = action.getDefaultEnvironment()
        env['BRANCH_ORDER'] = '7.0.x, 7.1.x, 8.0.x'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.getActionGroupLogs('Merge 7.0.x into 7.1.x').contains('Merged 7.0.x into 7.1.x.')

        and:
        gitRepo.getFileContents('README.md', '7.1.x') == '# merged from 7.0.x\n'
        gitRepo.getFileContents('README.md', '8.0.x') == '# demo\n'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def 'failure - errors when the next downstream merge conflicts'() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0', tagName: null, targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('cascade-merge', release)

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, ['7.0.x', '7.1.x', '8.0.x'])
        gitRepo.storeFiles(['README.md': '# source branch change\n'], '7.0.x')
        gitRepo.storeFiles(['README.md': '# conflicting target change\n'], '7.1.x')
        gitRepo.stageRepositoryForAction('7.0.x', false)

        and:
        def env = action.getDefaultEnvironment()
        env['BRANCH_ORDER'] = '7.0.x,7.1.x,8.0.x'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        def e = thrown(ContainerLaunchException)
        e.message.contains('Container startup failed')

        and:
        action.actionLogs.contains('ERROR: Merge conflict while merging 7.0.x into 7.1.x. Resolve manually.')

        and:
        gitRepo.getFileContents('README.md', '7.1.x') == '# conflicting target change\n'
        gitRepo.getFileContents('README.md', '8.0.x') == '# demo\n'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def 'success - does nothing when source branch is not in branch order'() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0', tagName: null, targetBranch: 'feature/test', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('cascade-merge', release)

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, ['7.0.x', '7.1.x', '8.0.x', 'feature/test'])
        gitRepo.storeFiles(['README.md': '# feature branch\n'], 'feature/test')
        gitRepo.stageRepositoryForAction('feature/test', false)

        and:
        def env = action.getDefaultEnvironment()
        env['BRANCH_ORDER'] = '7.0.x,7.1.x,8.0.x'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs.contains("SOURCE_BRANCH 'feature/test' was not found in BRANCH_ORDER. Nothing to merge.")

        and:
        gitRepo.getFileContents('README.md', '7.0.x') == '# demo\n'
        gitRepo.getFileContents('README.md', '7.1.x') == '# demo\n'
        gitRepo.getFileContents('README.md', '8.0.x') == '# demo\n'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }
}
