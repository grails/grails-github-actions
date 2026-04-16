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
import org.apache.grails.github.mocks.GitHubVersion
import org.apache.grails.github.mocks.GitHubRepoMock
import org.apache.grails.github.mocks.cli.GitHubCliMock
import org.testcontainers.containers.Network
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class PostReleaseSpec extends Specification {

    @Shared
    @AutoCleanup
    Network net = Network.newNetwork()

    @AutoCleanup
    GitHubDockerAction action

    @AutoCleanup
    GitHubRepoMock gitRepo

    def 'success - merge pr created - custom tag prefix'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'rel-7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('rel-7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('rel-7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'
        env['RELEASE_TAG_PREFIX'] = 'rel-'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Determine release version') == 'Release Version: 7.0.0-RC1'

        and: 'next version'
        action.getActionGroupLogs('Determine next version') == 'Next Version: 7.0.0'

        and: 'target branch'
        action.getActionGroupLogs('Determine target merge branch').contains('Target Branch is refs/heads/rel-7.0.0-RC1')
        action.getActionGroupLogs('Determine target merge branch').contains('Pruned Target Branch is rel-7.0.0-RC1')

        and: 'no release update'
        action.getActionGroupLogs('Update Release Status').contains('No release flags set (RELEASE_PRE_RELEASE / RELEASE_LATEST). Skipping GitHub Release update.')

        and: 'project version reverted'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-SNAPSHOT")

        and:
        gitRepo.branchExists('merge-back-7.0.0-RC1')

        and:
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('rel-7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "success - different property file name"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'], [
                'README.md'     : '# demo\n',
                'foo.properties': "projectVersion=7.0.0-SNAPSHOT\n"
        ])
        gitRepo.storeFiles(['foo.properties': "projectVersion=7.0.0-RC1\n"], 'v7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'
        env['PROPERTY_FILE_NAME'] = 'foo.properties'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Determine release version') == 'Release Version: 7.0.0-RC1'

        and: 'next version'
        action.getActionGroupLogs('Determine next version') == 'Next Version: 7.0.0'

        and: 'target branch'
        action.getActionGroupLogs('Determine target merge branch').contains('Target Branch is refs/heads/v7.0.0-RC1')
        action.getActionGroupLogs('Determine target merge branch').contains('Pruned Target Branch is v7.0.0-RC1')

        and: 'project version reverted'
        action.workspacePath.resolve('foo.properties').toFile().text.contains("projectVersion=7.0.0-SNAPSHOT")

        and:
        gitRepo.branchExists('merge-back-7.0.0-RC1')

        and:
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1', 'foo.properties') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('main', 'foo.properties') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1', 'foo.properties') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - merge pr created - tag v7.0.0-RC1 to 7.0.x branch'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Determine release version') == 'Release Version: 7.0.0-RC1'

        and: 'next version'
        action.getActionGroupLogs('Determine next version') == 'Next Version: 7.0.0'

        and: 'target branch'
        action.getActionGroupLogs('Determine target merge branch').contains('Target Branch is refs/heads/v7.0.0-RC1')
        action.getActionGroupLogs('Determine target merge branch').contains('Pruned Target Branch is v7.0.0-RC1')

        and: 'no release update'
        action.getActionGroupLogs('Update Release Status').contains('No release flags set (RELEASE_PRE_RELEASE / RELEASE_LATEST). Skipping GitHub Release update.')

        and: 'project version reverted'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-SNAPSHOT")

        and:
        gitRepo.branchExists('merge-back-7.0.0-RC1')

        and:
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - labels applied to created merge pr'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'
        env['PR_LABELS'] = 'skip-changelog, internal-release'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.getActionGroupLogs('Open/Reuse pull request').contains('https://github.com/mock-org/mock-repo/pull/42')
        action.getActionGroupLogs('Open/Reuse pull request').contains('Applying pull request labels: skip-changelog, internal-release')
        action.getActionGroupLogs('Open/Reuse pull request').contains('ref=https://github.com/mock-org/mock-repo/pull/42 labels=skip-changelog,internal-release')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - labels applied when merge pr already exists'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'exists'
        env['PR_LABELS'] = 'skip-changelog'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.getActionGroupLogs('Open/Reuse pull request').contains('Applying pull request labels: skip-changelog')
        action.getActionGroupLogs('Open/Reuse pull request').contains('ref=https://github.com/mock-org/mock-repo/pull/42 labels=skip-changelog')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'failure - create pr fails and no existing pr is found'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'fail'
        env['GH_MOCK_PR_VIEW'] = 'fail'
        env['RELEASE_LATEST'] = 'true'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        def e = thrown(org.testcontainers.containers.ContainerLaunchException)
        e.message.contains('Container startup failed')

        and: 'logs capture the real failure mode'
        action.actionLogs.contains('PR creation failed. Checking for an existing PR:')
        action.actionLogs.contains('gh-mock: simulated failure creating PR')
        action.actionLogs.contains('gh-mock: simulated failure viewing PR')
        action.actionLogs.contains('ERROR: Merge-back branch merge-back-7.0.0-RC1 was pushed, but pull request creation failed and no existing PR could be found. Create the PR manually.')

        and: 'merge branch and version bump were still produced before failing'
        gitRepo.branchExists('merge-back-7.0.0-RC1')
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1') == '7.0.0-SNAPSHOT'

        and: 'later workflow steps still executed before the final failure'
        action.getActionGroupLogs('Update Release Status').contains('PATCH payload: {"make_latest": "true"}')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - pre-release forced update'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'
        env['RELEASE_PRE_RELEASE'] = 'false'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Determine release version') == 'Release Version: 7.0.0-RC1'

        and: 'next version'
        action.getActionGroupLogs('Determine next version') == 'Next Version: 7.0.0'

        and: 'target branch'
        action.getActionGroupLogs('Determine target merge branch').contains('Target Branch is refs/heads/v7.0.0-RC1')
        action.getActionGroupLogs('Determine target merge branch').contains('Pruned Target Branch is v7.0.0-RC1')

        and: 'no release update'
        !action.getActionGroupLogs('Update Release Status').contains('No release flags set (RELEASE_PRE_RELEASE / RELEASE_LATEST). Skipping GitHub Release update.')
        action.getActionGroupLogs('Update Release Status').contains('PATCH payload: {"prerelease": false}')

        and: 'project version reverted'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-SNAPSHOT")

        and:
        gitRepo.branchExists('merge-back-7.0.0-RC1')

        and:
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - latest forced update'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'
        env['RELEASE_LATEST'] = 'true'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Determine release version') == 'Release Version: 7.0.0-RC1'

        and: 'next version'
        action.getActionGroupLogs('Determine next version') == 'Next Version: 7.0.0'

        and: 'target branch'
        action.getActionGroupLogs('Determine target merge branch').contains('Target Branch is refs/heads/v7.0.0-RC1')
        action.getActionGroupLogs('Determine target merge branch').contains('Pruned Target Branch is v7.0.0-RC1')

        and: 'no release update'
        !action.getActionGroupLogs('Update Release Status').contains('No release flags set (RELEASE_PRE_RELEASE / RELEASE_LATEST). Skipping GitHub Release update.')
        action.getActionGroupLogs('Update Release Status').contains('PATCH payload: {"make_latest": "true"}')

        and: 'project version reverted'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-SNAPSHOT")

        and:
        gitRepo.branchExists('merge-back-7.0.0-RC1')

        and:
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - latest and prerelease forced update'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', ['7.0.x'])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'
        env['RELEASE_LATEST'] = 'true'
        env['RELEASE_PRE_RELEASE'] = 'false'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Determine release version') == 'Release Version: 7.0.0-RC1'

        and: 'next version'
        action.getActionGroupLogs('Determine next version') == 'Next Version: 7.0.0'

        and: 'target branch'
        action.getActionGroupLogs('Determine target merge branch').contains('Target Branch is refs/heads/v7.0.0-RC1')
        action.getActionGroupLogs('Determine target merge branch').contains('Pruned Target Branch is v7.0.0-RC1')

        and: 'no release update'
        !action.getActionGroupLogs('Update Release Status').contains('No release flags set (RELEASE_PRE_RELEASE / RELEASE_LATEST). Skipping GitHub Release update.')
        action.getActionGroupLogs('Update Release Status').contains('PATCH payload: {"prerelease": false, "make_latest": "true"}')

        and: 'project version reverted'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-SNAPSHOT")

        and:
        gitRepo.branchExists('merge-back-7.0.0-RC1')

        and:
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - merge pr created - tag v7.0.0-RC1 to main branch'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: 'main', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('post-release', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', [])
        gitRepo.setProjectVersion('v7.0.0-RC1', '7.0.0-RC1')
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['GH_MOCK_PR_CREATE'] = 'create'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Determine release version') == 'Release Version: 7.0.0-RC1'

        and: 'next version'
        action.getActionGroupLogs('Determine next version') == 'Next Version: 7.0.0'

        and: 'target branch'
        action.getActionGroupLogs('Determine target merge branch').contains('Target Branch is refs/heads/v7.0.0-RC1')
        action.getActionGroupLogs('Determine target merge branch').contains('Pruned Target Branch is v7.0.0-RC1')

        and: 'no release update'
        action.getActionGroupLogs('Update Release Status').contains('No release flags set (RELEASE_PRE_RELEASE / RELEASE_LATEST). Skipping GitHub Release update.')

        and: 'project version reverted'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-SNAPSHOT")

        and:
        gitRepo.branchExists('merge-back-7.0.0-RC1')

        and:
        gitRepo.getRefProjectVersion('merge-back-7.0.0-RC1') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }
}
