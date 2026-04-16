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
import org.testcontainers.containers.Network
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class PreReleaseSpec extends Specification {

    @Shared
    @AutoCleanup
    Network net = Network.newNetwork()

    @AutoCleanup
    GitHubDockerAction action

    @AutoCleanup
    GitHubRepoMock gitRepo

    def 'success - tag v7.0.0-RC1 updated with version'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: 'main', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('pre-release', release)

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', [])
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Setup').contains('Release Version: 7.0.0-RC1')

        and: 'next version'
        action.getActionGroupLogs('Pushing Project Changes').contains('Pushing release version and recreating v7.0.0-RC1 tag')

        and: 'target branch'
        action.getActionGroupLogs('Updating Release for Project Changes').contains('Pre Release steps complete')

        and: 'project version updated'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-RC1")

        and:
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - different property file name'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'v7.0.0-RC1', targetBranch: 'main', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('pre-release', release)

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'v7.0.0-RC1', [], [
                'README.md'     : '# demo\n',
                'foo.properties': "projectVersion=7.0.0-SNAPSHOT\n"
        ])
        gitRepo.stageRepositoryForAction('v7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['PROPERTY_FILE_NAME'] = 'foo.properties'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Setup').contains('Release Version: 7.0.0-RC1')

        and: 'next version'
        action.getActionGroupLogs('Pushing Project Changes').contains('Pushing release version and recreating v7.0.0-RC1 tag')

        and: 'target branch'
        action.getActionGroupLogs('Updating Release for Project Changes').contains('Pre Release steps complete')

        and: 'project version updated'
        action.workspacePath.resolve('foo.properties').toFile().text.contains("projectVersion=7.0.0-RC1")

        and:
        gitRepo.getRefProjectVersion('main', 'foo.properties') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('v7.0.0-RC1', 'foo.properties') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - tag with custom prefix updated with version'() {
        given:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: 'main', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('pre-release', release)

        gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', 'rel-7.0.0-RC1', [])
        gitRepo.stageRepositoryForAction('rel-7.0.0-RC1', true)

        and:
        def env = action.getDefaultEnvironment()
        env['RELEASE_TAG_PREFIX'] = 'rel-'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'release version'
        action.getActionGroupLogs('Setup').contains('Release Version: 7.0.0-RC1')

        and: 'next version'
        action.getActionGroupLogs('Pushing Project Changes').contains('Pushing release version and recreating rel-7.0.0-RC1 tag')

        and: 'target branch'
        action.getActionGroupLogs('Updating Release for Project Changes').contains('Pre Release steps complete')

        and: 'project version updated'
        action.workspacePath.resolve('gradle.properties').toFile().text.contains("projectVersion=7.0.0-RC1")

        and:
        gitRepo.getRefProjectVersion('main') == '7.0.0-SNAPSHOT'
        gitRepo.getRefProjectVersion('rel-7.0.0-RC1') == '7.0.0-RC1'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }
}
