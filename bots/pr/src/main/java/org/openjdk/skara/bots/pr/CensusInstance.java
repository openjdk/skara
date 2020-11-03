/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.census.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.jcheck.JCheckConfiguration;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

class CensusInstance {
    private final Census census;
    private final JCheckConfiguration configuration;
    private final Project project;
    private final Namespace namespace;

    private CensusInstance(Census census, JCheckConfiguration configuration, Project project, Namespace namespace) {
        this.census = census;
        this.configuration = configuration;
        this.project = project;
        this.namespace = namespace;
    }

    private static Project project(JCheckConfiguration configuration, Census census) {
        var project = census.project(configuration.general().project());

        if (project == null) {
            throw new RuntimeException("Project not found in census: " + configuration.general().project());
        }

        return project;
    }

    private static Namespace namespace(Census census, String hostNamespace) {
        //var namespace = census.namespace(pr.repository().getNamespace());
        var namespace = census.namespace(hostNamespace);
        if (namespace == null) {
            throw new RuntimeException("Namespace not found in census: " + hostNamespace);
        }

        return namespace;
    }

    private static JCheckConfiguration configuration(HostedRepositoryPool hostedRepositoryPool, HostedRepository remoteRepo, String name, String ref) throws IOException {
        var confFile = hostedRepositoryPool.lines(remoteRepo, Path.of(name), ref).orElseThrow(
                () -> new IOException("Failed to read jcheck configuration from " + name + ":" + ref));
        return JCheckConfiguration.parse(confFile);
    }

    static CensusInstance create(HostedRepositoryPool hostedRepositoryPool,
                                 HostedRepository censusRepo, String censusRef, Path folder, PullRequest pr,
                                 HostedRepository confOverrideRepo, String confOverrideName, String confOverrideRef) {
        return create(hostedRepositoryPool, censusRepo, censusRef, folder, pr.repository(), pr.targetRef(),
                      confOverrideRepo, confOverrideName, confOverrideRef);
    }

    static CensusInstance create(HostedRepositoryPool hostedRepositoryPool,
                                 HostedRepository censusRepo, String censusRef, Path folder, HostedRepository repository, String ref,
                                 HostedRepository confOverrideRepo, String confOverrideName, String confOverrideRef) {
        var repoName = censusRepo.url().getHost() + "/" + censusRepo.name();
        var repoFolder = folder.resolve(URLEncoder.encode(repoName, StandardCharsets.UTF_8));
        try {
            hostedRepositoryPool.checkoutAllowStale(censusRepo, censusRef, repoFolder);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot materialize census to " + repoFolder, e);
        }

        try {
            JCheckConfiguration configuration;
            if (confOverrideRepo == null) {
                configuration = configuration(hostedRepositoryPool, repository, ".jcheck/conf", ref);
            } else {
                configuration = configuration(hostedRepositoryPool,
                                              confOverrideRepo,
                                              confOverrideName,
                                              confOverrideRef);
            }
            var census = Census.parse(repoFolder);
            var project = project(configuration, census);
            var namespace = namespace(census, repository.namespace());
            return new CensusInstance(census, configuration, project, namespace);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse census at " + repoFolder, e);
        }
    }

    Census census() {
        return census;
    }

    JCheckConfiguration configuration() {
        return configuration;
    }

    Project project() {
        return project;
    }

    Namespace namespace() {
        return namespace;
    }

    Optional<Contributor> contributor(HostUser hostUser) {
        var contributor = namespace.get(hostUser.id());
        return Optional.ofNullable(contributor);
    }

    boolean isAuthor(HostUser hostUser) {
        int version = census.version().format();
        var contributor = namespace.get(hostUser.id());
        if (contributor == null) {
            return false;
        }
        return project.isAuthor(contributor.username(), version);
    }

    boolean isCommitter(HostUser hostUser) {
        int version = census.version().format();
        var contributor = namespace.get(hostUser.id());
        if (contributor == null) {
            return false;
        }
        return project.isCommitter(contributor.username(), version);
    }

    boolean isReviewer(HostUser hostUser) {
        int version = census.version().format();
        var contributor = namespace.get(hostUser.id());
        if (contributor == null) {
            return false;
        }
        return project.isReviewer(contributor.username(), version);
    }
}
