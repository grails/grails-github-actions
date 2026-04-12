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
package org.apache.grails.github.mocks

import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheBuilder
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.lib.SubmoduleConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.URIish
import org.testcontainers.containers.Container
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class GitHubRepoMock implements Closeable {

    GenericContainer gitHost
    Network network
    Path actionRepoPath

    GitHubRepoMock(Path actionRepoPath, Network network) {
        this.actionRepoPath = actionRepoPath
        this.network = network
    }

    /**
     * Starts up the git server in a container & setups initial credentials
     */
    void init() {
        gitHost = new GenericContainer<>('gitea/gitea:1.24')
                .withNetwork(network).withNetworkAliases('gitea')
                .withExposedPorts(3000)
                .withEnv('GITEA__server__ROOT_URL', 'http://gitea:3000/')
                .withEnv('GITEA__security__INSTALL_LOCK', 'true')
                .withEnv('GITEA__service__DISABLE_REGISTRATION', 'true')
                .withEnv('GITEA__database__DB_TYPE', 'sqlite3')
                .withEnv('GITEA__repository__DEFAULT_BRANCH', 'main')
                .waitingFor(Wait.forHttp('/').forPort(3000))
        gitHost.start()
        Container.ExecResult create = gitHost.execInContainer(
                'su-exec', 'git',
                'gitea', 'admin', 'user', 'create',
                '--admin', '--username', username, '--password', token,
                '--email', 'ci@example.com', '--must-change-password=false'
        )
        if (create.getExitCode() != 0) {
            throw new IllegalStateException("Create user failed: ${create.getStderr()}" as String)
        }

        String base = "http://${gitHost.getHost()}:${gitHost.getMappedPort(3000)}" as String
        HttpClient http = HttpClient.newHttpClient()
        String basic = "Basic ${Base64.getEncoder().encodeToString("$username:$token".getBytes(StandardCharsets.UTF_8))}" as String

        HttpRequest createRepo = HttpRequest.newBuilder(
                URI.create("$base/api/v1/user/repos" as String))
                .header('Authorization', basic)
                .header('Content-Type', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"widgets\",\"private\":false}"))
                .build()
        def response = http.send(createRepo, HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 201
    }

    String getUsername() {
        'acme'
    }

    String getToken() {
        'pass'
    }

    String getInternalUrlBase() {
        'gitea:3000'
    }

    String getExternalGitHostUrl() {
        "http://$username:$token@${gitHost.getHost()}:${gitHost.getMappedPort(3000)}/$username/widgets.git"
    }

    Map<String, String> getProjectVersionFiles(String storedProjectVersion) {
        [
                'README.md'        : '# demo\n',
                'gradle.properties': "projectVersion=$storedProjectVersion\n"
        ]
    }

    void createDivergedBranch(Map<String, String> files, String divergedBranch, String defaultBranch = 'main') {
        Path temp = Files.createTempDirectory('ref-project-version')
        try {
            cloneRepo(temp, defaultBranch) { Git git ->
                Repository repo = git.getRepository()

                try (ObjectInserter oi = repo.newObjectInserter()) {
                    DirCache inCore = DirCache.newInCore()
                    DirCacheBuilder builder = inCore.builder()

                    def entries = files.collect { k, v ->
                        String gitPath = k.replaceAll('^\\./+', '').replace('\\', '/').replaceAll('/+', '/')
                        [path: gitPath, bytes: v.getBytes(StandardCharsets.UTF_8)]
                    }.sort { a, b -> a.path <=> b.path }

                    entries.each { e ->
                        ObjectId blobId = oi.insert(Constants.OBJ_BLOB, e.bytes)
                        DirCacheEntry dce = new DirCacheEntry(e.path)
                        dce.setFileMode(FileMode.REGULAR_FILE)
                        dce.setObjectId(blobId)
                        builder.add(dce)
                    }
                    builder.finish()

                    ObjectId treeId = inCore.writeTree(oi)

                    PersonIdent who = new PersonIdent('diverged', 'diverged@example.com')
                    CommitBuilder cb = new CommitBuilder()
                    cb.setTreeId(treeId)
                    cb.setAuthor(who)
                    cb.setCommitter(who)
                    cb.setMessage('initial diverged commit')
                    ObjectId commitId = oi.insert(cb)
                    oi.flush()

                    String refName = Constants.R_HEADS + divergedBranch
                    RefUpdate ru = repo.updateRef(refName)
                    ru.setExpectedOldObjectId(ObjectId.zeroId()) // fail if it already exists
                    ru.setNewObjectId(commitId)
                    ru.setRefLogMessage("create orphan branch $divergedBranch", false)
                    RefUpdate.Result result = ru.update()
                    assert result in [RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED]:
                            "Ref update failed: $result"

                    repo.updateRef(Constants.HEAD).link(refName)

                    RefSpec spec = new RefSpec("${refName}:${refName}")

                    def push = git.push().setRemote('origin').setRefSpecs(spec)
                    assert push.call()
                }
            }
        }
        finally {
            temp.toFile().deleteDir()
        }
    }

    void populateRepository(String storedProjectVersion, String tag, List<String> additionalBranchNames = ['7.0.x'], Map<String, String> files = null) {
        if (!files) {
            files = getProjectVersionFiles(storedProjectVersion)
        }

        Path temp = Files.createTempDirectory('populate-repo')
        try {
            try (Repository repo = FileRepositoryBuilder.create(temp.resolve('.git').toFile())) {
                repo.create()

                try (Git git = Git.open(temp.toFile())) {
                    for (Map.Entry<String, String> entry : files.entrySet()) {
                        def fileToCreate = temp.resolve(entry.key)
                        fileToCreate.toFile().parentFile.mkdirs()
                        Files.writeString(fileToCreate, entry.value)
                    }
                    assert git.add().addFilepattern('.').call()
                    assert git.commit()
                            .setAuthor('CI', 'ci@example.com')
                            .setCommitter('CI', 'ci@example.com')
                            .setSign(false)
                            .setMessage('init').call()

                    assert git.branchRename().setNewName('main').call()

                    for (String additionalBranch : additionalBranchNames) {
                        assert git.branchCreate().setName(additionalBranch).setStartPoint('main').call()
                    }

                    if (tag) {
                        def tagRef = git.tag().setName(tag)
                                .setMessage('My Release Message')
                                .call()
                        assert tagRef
                    }

                    assert git.remoteAdd().setName('origin').setUri(new URIish(externalGitHostUrl)).call()

                    List<RefSpec> specs = ['main', additionalBranchNames].flatten().unique().collect { String branch ->
                        new RefSpec("${Constants.R_HEADS + branch}:${Constants.R_HEADS + branch}" as String)
                    }

                    def push = git.push()
                            .setRemote('origin')
                            .setRefSpecs(specs)
                            .setPushAll()
                            .setPushTags()
                    def pushResult = push.call()
                    assert pushResult

                    specs.each { RefSpec spec ->
                        assert repo.findRef(spec.destination)
                    }
                }
            }
        }
        finally {
            temp.toFile().deleteDir()
        }
    }

    String getInternalGitHostUrl() {
        "http://$username:$token@$internalUrlBase/$username/widgets.git"
    }

    void stageRepositoryForAction(String refName, boolean isTag) {
        try (Git git = Git.init().setDirectory(actionRepoPath.toFile()).call()) {
            Repository repo = git.getRepository();
            StoredConfig cfg = repo.getConfig();

            // git remote add origin externalGitHostUrl
            cfg.setString("remote", "origin", "url", externalGitHostUrl)
            cfg.setStringList("remote", "origin", "fetch", Arrays.asList("+refs/heads/*:refs/remotes/origin/*"))
            cfg.save()

            // bug with jgit, the '*' isn't expanding so use the exact instead. Note: jgit seems to be strict, while the git commandline ignores mismatches
            List<RefSpec> refSpecs = [isTag ? new RefSpec("+refs/tags/$refName:refs/tags/$refName") : new RefSpec("+refs/heads/$refName:refs/remotes/origin/$refName")]

            // git -c protocol.version=2 fetch --no-tags --prune --no-recurse-submodules --depth=1 origin \
            //   '+refs/heads/v7.0.0-RC1*:refs/remotes/origin/v7.0.0-RC1*' \
            //   '+refs/tags/v7.0.0-RC1*:refs/tags/v7.0.0-RC1*'
            FetchCommand fetch = git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(
                            refSpecs
                    )
                    .setTagOpt(TagOpt.NO_TAGS)
                    .setRemoveDeletedRefs(true)
                    .setRecurseSubmodules(SubmoduleConfig.FetchRecurseSubmodulesMode.NO)
            if (isTag) {
                fetch.setDepth(1)
            }
            assert fetch.call()

            // git checkout --force refs/tags/v7.0.0-RC1
            git.checkout()
                    .setName(isTag ? "refs/tags/$refName" : "refs/remotes/origin/$refName") // detached HEAD if a tag
                    .setForced(true)
                    .call()

            // reset to internal git url since the container will be connecting to a different endpoint
            assert git.remoteSetUrl().setRemoteName('origin').setRemoteUri(new URIish(internalGitHostUrl)).call()
        }
    }

    void storeFiles(Map<String, String> files, String refName) {
        storeFiles(files, refName, "store files :${files.keySet().join(',')}")
    }

    void storeFiles(Map<String, String> files, String refName, String commitMessage) {
        Path temp = Files.createTempDirectory('store-files')
        try {
            cloneRepo(temp, refName) { Git git ->
                for (Map.Entry<String, String> fileEntry : files.entrySet()) {
                    def fileToCreate = temp.resolve(fileEntry.key)
                    fileToCreate.toFile().parentFile.mkdirs()
                    fileToCreate.toFile().text = fileEntry.value
                }
                git.add().addFilepattern('.').call()
                assert git.add().addFilepattern('.').call()
                assert git.commit()
                        .setAuthor('CI', 'ci@example.com')
                        .setCommitter('CI', 'ci@example.com')
                        .setSign(false)
                        .setMessage(commitMessage).call()

                def tagRef = git.repository.findRef("${Constants.R_TAGS + refName}")
                if (tagRef != null) {
                    // It's a tag: delete and recreate tag at new commit
                    git.tagDelete().setTags(refName).call()
                    git.tag().setName(refName).setMessage("Updated tag $refName").call()
                    def pushResult = git.push()
                            .setRemote('origin')
                            .setRefSpecs(new RefSpec("${Constants.R_TAGS + refName}:${Constants.R_TAGS + refName}"))
                            .setPushTags()
                            .setForce(true)
                            .call()
                    assert pushResult
                } else {
                    PushCommand push = git.push()
                            .setRemote('origin')
                            .setRefSpecs(new RefSpec("${Constants.R_HEADS + refName}:${Constants.R_HEADS + refName}" as String))
                            .setPushAll()
                            .setPushTags()
                    def pushResult = push.call()
                    assert pushResult
                }
            }
        }
        finally {
            temp.toFile().deleteDir()
        }
    }

    void setProjectVersion(String refName, String newProjectVersion) {
        storeFiles(['gradle.properties': "projectVersion=${newProjectVersion}\n"], refName)
    }

    boolean branchExists(String branchName) {
        Path temp = Files.createTempDirectory('ref-project-version')
        try {
            cloneRepo(temp, branchName)
            return true
        }
        catch (e) {
            return false
        }
        finally {
            temp.toFile().deleteDir()
        }
    }

    private void cloneRepo(Path targetDirectory, String checkoutRef, Closure c = null) {
        try (Git git = Git.cloneRepository().setURI(externalGitHostUrl).setDirectory(targetDirectory.toFile()).setCloneAllBranches(true).call()) {
            git.fetch().setTagOpt(TagOpt.FETCH_TAGS).call()

            if (checkoutRef != 'main') {
                def repo = git.getRepository()
                def ref = repo.findRef(checkoutRef)
                if (ref == null) {
                    // Try as remote branch
                    ref = repo.findRef("${Constants.R_REMOTES + 'origin/' + checkoutRef}")
                }
                if (ref == null) {
                    throw new IllegalArgumentException("Ref 'checkoutRef' does not exist in remote repository")
                }

                if (ref.getName().startsWith(Constants.R_TAGS)) {
                    // Checkout tag in detached HEAD
                    git.checkout().setName(checkoutRef).call()
                } else {
                    // Checkout branch, create if needed
                    git.checkout().setCreateBranch(true).setName(checkoutRef).setStartPoint("origin/${checkoutRef}").call()
                }
            }
            // else main already checked out

            if (c) {
                c.call(git)
            }
        }
    }

    List<String> getFolders(String basePath = null, String refName) {
        Path temp = Files.createTempDirectory('list-folders')
        try {
            List<String> folders = []
            cloneRepo(temp, refName) { Git git ->
                Path expectedBasePath = basePath ? temp.resolve(basePath) : temp
                if (!Files.exists(expectedBasePath)) {
                    throw new IllegalStateException(
                            "Could not resolve '$basePath' at $refName. Ref or path may not exist (or ref wasn’t fetched).");
                }

                folders = expectedBasePath.toFile().listFiles({ File pathname ->
                    pathname.isDirectory() && pathname.name != '.git'
                } as FileFilter).collect { it.name }
            }
            return folders
        }
        finally {
            temp.toFile().deleteDir()
        }
    }

    String getFileContents(String filename, String refName) {
        Path temp = Files.createTempDirectory('file-contents')
        try {
            String contents = null
            cloneRepo(temp, refName) { Git git ->
                Path fileToReturn = temp.resolve(filename)
                if (!Files.exists(fileToReturn)) {
                    throw new IllegalStateException(
                            "Could not resolve '$filename' at $refName. Ref or file may not exist (or ref wasn’t fetched).");
                }

                contents = fileToReturn.toFile().text
            }
            return contents
        }
        finally {
            temp.toFile().deleteDir()
        }
    }

    String getRefProjectVersion(String refName, String propertyFilename = 'gradle.properties') {
        String contents = getFileContents(propertyFilename, refName)
        def matcher = (contents =~ /projectVersion=(.+)/)
        if (matcher) {
            return matcher[0][1] as String
        } else {
            throw new IllegalStateException("Could not find projectVersion in '$propertyFilename' for ref $refName" as String)
        }
    }

    void close() {
        gitHost?.stop()
    }
}
