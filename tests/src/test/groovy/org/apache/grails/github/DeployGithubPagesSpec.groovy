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

class DeployGithubPagesSpec extends Specification {

    @Shared
    @AutoCleanup
    Network net = Network.newNetwork()

    @AutoCleanup
    GitHubDockerAction action

    @AutoCleanup
    GitHubRepoMock gitRepo

    private Map<String, String> getProjectFiles() {
        [
                'gradle.properties': 'projectVersion=7.0.0-SNAPSHOT',
                'docs/index.html'  : '<html><body>Welcome to the Grails Documentation</body></html>',
                'docs/ghpages.html': '<html><body>Welcome to the Grails GitHub Pages</body></html>',
        ]
    }

    private Map<String, String> getDefaultEnvironment(GitHubDockerAction action, GitHubRepoMock gitRepo) {
        def env = action.getDefaultEnvironment()

        env['GITHUB_USER_NAME'] = gitRepo.getUsername()
        env['GH_TOKEN'] = gitRepo.getToken()
        env['GIT_TRANSFER_PROTOCOL'] = 'http'
        env['GITHUB_URL_BASE'] = gitRepo.getInternalUrlBase()

        env
    }

    def "gh-pages branch is created if does not exist"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch').contains('Creating documentation branch gh-pages as it does not exist')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "ghpages_html is set as root index_html"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.createDivergedBranch([
                'index.html'         : 'will be replaced',
                'snapshot/index.html': 'will also be replaced'
        ], 'gh-pages')
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        !action.isLogGroupPresent('Creating documentation branch')
        action.getActionGroupLogs('Checkout documentation branch').contains('documentation branch found, cloning')

        and: 'ghpages copied'
        action.getActionGroupLogs('Staging root index.html')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        and: 'gh-pages replaced expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "snapshot - snapshot publishing disabled"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SKIP_SNAPSHOT_FOLDER'] = 'true'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        !gitRepo.branchExists('gh-pages')

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "snapshot - published with subfolder"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['TARGET_SUBFOLDER'] = 'nested'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFolders('snapshot', 'gh-pages') == ['nested']
        gitRepo.getFileContents('snapshot/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "snapshot - published without subfolder"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFolders('snapshot', 'gh-pages') == []
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "snapshot - published to different base path without subfolder"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['TARGET_FOLDER'] = 'my/base/path'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFolders('my/base/path/snapshot', 'gh-pages') == []
        gitRepo.getFileContents('my/base/path/snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['my']
        gitRepo.getFolders('my', 'gh-pages') == ['base']
        gitRepo.getFolders('my/base', 'gh-pages') == ['path']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "snapshot - version is ignored on snapshot"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = 'BAD_VERSION_THAT_SHOULD_NOT_CAUSE_FAILURE'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFolders('snapshot', 'gh-pages') == []
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "release - published without subfolder"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'true'
        env['SKIP_SNAPSHOT_FOLDER'] = 'true' // should be ignored because this is a release
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-RC1'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        !action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        action.getActionGroupLogs('Publishing Specific Release Version: 7.0.0-RC1')
        action.getActionGroupLogs('Publishing Generic Release Version: 7.0.x')
        action.getActionGroupLogs('Overwriting latest with the latest release documentation')

        and:
        gitRepo.branchExists('gh-pages')

        and:
        gitRepo.getFolders('gh-pages').sort() == ['7.0.0-RC1', '7.0.x', 'latest']
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        and:
        gitRepo.getFileContents('latest/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.x/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.0-RC1/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "release - published to different base path without subfolder"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'true'
        env['SKIP_SNAPSHOT_FOLDER'] = 'true' // should be ignored because this is a release
        env['TARGET_FOLDER'] = 'my/base/path'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-RC1'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        !action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        action.getActionGroupLogs('Publishing Specific Release Version: 7.0.0-RC1')
        action.getActionGroupLogs('Publishing Generic Release Version: 7.0.x')
        action.getActionGroupLogs('Overwriting latest with the latest release documentation')

        and:
        gitRepo.branchExists('gh-pages')

        and:
        gitRepo.getFolders('gh-pages') == ['my']
        gitRepo.getFolders('my', 'gh-pages') == ['base']
        gitRepo.getFolders('my/base', 'gh-pages') == ['path']
        gitRepo.getFolders('my/base/path', 'gh-pages').sort() == ['7.0.0-RC1', '7.0.x', 'latest']
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        and:
        gitRepo.getFileContents('my/base/path/latest/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('my/base/path/7.0.x/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('my/base/path/7.0.0-RC1/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "release - published with subfolder"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'true'
        env['SKIP_SNAPSHOT_FOLDER'] = 'true' // should be ignored because this is a release
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-RC1'
        env['TARGET_SUBFOLDER'] = 'nested'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        !action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        action.getActionGroupLogs('Publishing Specific Release Version: 7.0.0-RC1')
        action.getActionGroupLogs('Publishing Generic Release Version: 7.0.x')
        action.getActionGroupLogs('Overwriting latest with the latest release documentation')

        and:
        gitRepo.branchExists('gh-pages')

        and:
        gitRepo.getFolders('gh-pages').sort() == ['7.0.0-RC1', '7.0.x', 'latest']
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        and:
        gitRepo.getFileContents('latest/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.x/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.0-RC1/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "release - skip publishing to latest"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'true'
        env['SKIP_SNAPSHOT_FOLDER'] = 'true' // should be ignored because this is a release
        env['SKIP_RELEASE_FOLDER'] = 'true'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-RC1'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        !action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        action.getActionGroupLogs('Publishing Specific Release Version: 7.0.0-RC1')
        action.getActionGroupLogs('Publishing Generic Release Version: 7.0.x')
        !action.isLogGroupPresent('Overwriting latest with the latest release documentation')

        and:
        gitRepo.branchExists('gh-pages')

        and:
        gitRepo.getFolders('gh-pages').sort() == ['7.0.0-RC1', '7.0.x']
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        and:
        gitRepo.getFileContents('7.0.x/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.0-RC1/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders('main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "push retry - logs show successful deployment on first attempt"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L

        and: 'retry loop logs first attempt and success'
        def commitLogs = action.getActionGroupLogs('Committing Changes')
        commitLogs.contains('Push attempt 1/5')
        commitLogs.contains('Deployment successful!')
        !commitLogs.contains('Push attempt 2/5')

        and: 'docs deployed'
        gitRepo.branchExists('gh-pages')
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "push retry - rebases and preserves a competing disjoint commit"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.createDivergedBranch([
                'index.html'         : '<html><body>Existing root page</body></html>',
                'snapshot/index.html': '<html><body>Existing snapshot</body></html>'
        ], 'gh-pages')
        gitRepo.stageRepositoryForAction('main', false)

        and: 'install a wrapper that publishes a competing commit immediately before the first push'
        def gitWrapper = action.mockPath.resolve('git').toFile()
        gitWrapper.text = '''\
#!/bin/sh
REAL_GIT=/usr/bin/git
MARKER=/tmp/git_push_competing_commit
if [ "$1" = "push" ]; then
  if [ ! -f "$MARKER" ]; then
    touch "$MARKER"
    ACTION_CHECKOUT="$(pwd)"
    rm -rf /tmp/concurrent-publisher
    for arg in "$@"; do
      case "$arg" in
        http://*|https://*) REMOTE="$arg" ;;
      esac
    done
    "$REAL_GIT" clone "$REMOTE" /tmp/concurrent-publisher
    cd /tmp/concurrent-publisher
    "$REAL_GIT" checkout gh-pages
    mkdir -p publisher
    printf '%s\\n' 'winner' > publisher/winner.html
    "$REAL_GIT" add publisher/winner.html
    "$REAL_GIT" -c user.name=winner -c user.email=winner@example.com commit -m winner
    "$REAL_GIT" push origin HEAD:refs/heads/gh-pages
    cd "$ACTION_CHECKOUT"
  fi
fi
exec "$REAL_GIT" "$@"
'''
        gitWrapper.executable = true

        action.mockPath.resolve('sleep').toFile().with {
            text = '#!/bin/sh\nexit 0\n'
            executable = true
        }

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L

        and: 'the actual non-fast-forward rejection was rebased and retried'
        action.actionLogs.contains('Push attempt 1/5')
        action.actionLogs.contains('Push rejected by a concurrent publisher, rebasing and retrying...')
        action.actionLogs.contains('Push attempt 2/5')
        action.actionLogs.contains('Deployment successful!')

        and: 'both publishers changes survive'
        gitRepo.branchExists('gh-pages')
        gitRepo.getFileContents('publisher/winner.html', 'gh-pages').trim() == 'winner'
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "push retry - aborts a conflicting rebase and preserves the winner"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.createDivergedBranch([
                'index.html'         : '<html><body>Existing root page</body></html>',
                'snapshot/index.html': '<html><body>Existing snapshot</body></html>'
        ], 'gh-pages')
        gitRepo.stageRepositoryForAction('main', false)

        and: 'install a wrapper that changes the same destination file immediately before the first push'
        def gitWrapper = action.mockPath.resolve('git').toFile()
        gitWrapper.text = '''\
#!/bin/sh
REAL_GIT=/usr/bin/git
MARKER=/tmp/git_push_conflicting_commit
if [ "$1" = "push" ] && [ ! -f "$MARKER" ]; then
  touch "$MARKER"
  ACTION_CHECKOUT="$(pwd)"
  rm -rf /tmp/concurrent-publisher
  for arg in "$@"; do
    case "$arg" in
      http://*|https://*) REMOTE="$arg" ;;
    esac
  done
  "$REAL_GIT" clone "$REMOTE" /tmp/concurrent-publisher
  cd /tmp/concurrent-publisher
  "$REAL_GIT" checkout gh-pages
  printf '%s\\n' 'winner' > snapshot/index.html
  "$REAL_GIT" add snapshot/index.html
  "$REAL_GIT" -c user.name=winner -c user.email=winner@example.com commit -m winner
  "$REAL_GIT" push origin HEAD:refs/heads/gh-pages
  cd "$ACTION_CHECKOUT"
fi
exec "$REAL_GIT" "$@"
'''
        gitWrapper.executable = true

        action.mockPath.resolve('sleep').toFile().with {
            text = '#!/bin/sh\nexit 0\n'
            executable = true
        }

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        Exception startupException = null
        try {
            action.runAction()
        } catch (Exception e) {
            startupException = e
        }

        then: 'action fails without resolving the conflict'
        startupException != null || action.actionExitCode != 0L

        and: 'the failed rebase was aborted and no second push was attempted'
        action.actionLogs.contains('Push attempt 1/5')
        !action.actionLogs.contains('Push attempt 2/5')
        action.actionLogs.contains('ERROR: Rebase failed; aborting without changing the remote branch.')
        !action.workspacePath.resolve('gh-pages/.git/rebase-merge').toFile().exists()
        !action.workspacePath.resolve('gh-pages/.git/rebase-apply').toFile().exists()

        and: 'the winner remains on the remote'
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages').trim() == 'winner'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "push retry - fails a non-contention push error without fetching or retrying"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.createDivergedBranch([
                'index.html'         : '<html><body>Existing root page</body></html>',
                'snapshot/index.html': '<html><body>Existing snapshot</body></html>'
        ], 'gh-pages')
        gitRepo.stageRepositoryForAction('main', false)

        and: 'install a wrapper that returns a generic push failure and exposes any fetch'
        def gitWrapper = action.mockPath.resolve('git').toFile()
        gitWrapper.text = '''\
#!/bin/sh
if [ "$1" = "push" ]; then
  echo 'fatal: authentication failed' >&2
  exit 1
fi
if [ "$1" = "fetch" ]; then
  echo 'WRAPPER_FETCH_CALLED' >&2
fi
exec /usr/bin/git "$@"
'''
        gitWrapper.executable = true

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        Exception startupException = null
        try {
            action.runAction()
        } catch (Exception e) {
            startupException = e
        }

        then: 'the generic failure is not treated as a retryable race'
        startupException != null || action.actionExitCode != 0L
        action.actionLogs.contains('Push attempt 1/5')
        !action.actionLogs.contains('Push attempt 2/5')
        !action.actionLogs.contains('WRAPPER_FETCH_CALLED')
        action.actionLogs.contains('ERROR: Push failed without a retryable non-fast-forward rejection.')

        and:
        !action.actionLogs.contains('Deployment successful!')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def "push retry - fails after five competing remote advances"() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.createDivergedBranch([
                'index.html'         : '<html><body>Existing root page</body></html>',
                'snapshot/index.html': '<html><body>Existing snapshot</body></html>'
        ], 'gh-pages')
        gitRepo.stageRepositoryForAction('main', false)

        and: 'install a wrapper that advances the remote before every action push'
        def gitWrapper = action.mockPath.resolve('git').toFile()
        gitWrapper.text = '''\
#!/bin/sh
REAL_GIT=/usr/bin/git
COUNT_FILE=/tmp/git_push_advance_count
if [ "$1" = "push" ]; then
  ACTION_CHECKOUT="$(pwd)"
  COUNT=0
  if [ -f "$COUNT_FILE" ]; then
    COUNT="$(cat "$COUNT_FILE")"
  fi
  COUNT=$((COUNT + 1))
  printf '%s\\n' "$COUNT" > "$COUNT_FILE"
  rm -rf /tmp/concurrent-publisher
  for arg in "$@"; do
    case "$arg" in
      http://*|https://*) REMOTE="$arg" ;;
    esac
  done
  "$REAL_GIT" clone "$REMOTE" /tmp/concurrent-publisher
  cd /tmp/concurrent-publisher
  "$REAL_GIT" checkout gh-pages
  mkdir -p publisher
  printf '%s\\n' "$COUNT" > "publisher/winner-${COUNT}.html"
  "$REAL_GIT" add "publisher/winner-${COUNT}.html"
  "$REAL_GIT" -c user.name=winner -c user.email=winner@example.com commit -m "winner ${COUNT}"
  "$REAL_GIT" push origin HEAD:refs/heads/gh-pages
  cd "$ACTION_CHECKOUT"
fi
exec "$REAL_GIT" "$@"
'''
        gitWrapper.executable = true

        action.mockPath.resolve('sleep').toFile().with {
            text = '#!/bin/sh\nexit 0\n'
            executable = true
        }

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        Exception startupException = null
        try {
            action.runAction()
        } catch (Exception e) {
            startupException = e
        }

        then: 'each normal push is rejected by a fresh remote advance'
        startupException != null || action.actionExitCode != 0L
        action.actionLogs.contains('Push attempt 1/5')
        action.actionLogs.contains('Push attempt 2/5')
        action.actionLogs.contains('Push attempt 3/5')
        action.actionLogs.contains('Push attempt 4/5')
        action.actionLogs.contains('Push attempt 5/5')
        !action.actionLogs.contains('Push attempt 6/5')
        action.actionLogs.contains('ERROR: Push failed after 5 attempts.')
        !action.actionLogs.contains('Deployment successful!')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }
}
