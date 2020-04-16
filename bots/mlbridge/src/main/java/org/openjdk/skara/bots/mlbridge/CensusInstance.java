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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.census.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

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

    private static Repository initialize(HostedRepository repo, String ref, Path folder) {
        try {
            return Repository.materialize(folder, repo.url(), "+" + ref + ":" + "mlbridge_census_" + repo.name());
        } catch (IOException e) {
            throw new RuntimeException("Failed to retrieve census to " + folder, e);
        }
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

    private static JCheckConfiguration configuration(HostedRepository remoteRepo, String ref) {
        var confFile = remoteRepo.fileContents(".jcheck/conf", ref);
        return JCheckConfiguration.parse(confFile.lines().collect(Collectors.toList()));
    }

    static CensusInstance create(HostedRepository censusRepo, String censusRef, Path folder, PullRequest pr) {
        var repoName = censusRepo.url().getHost() + "/" + censusRepo.name();
        var repoFolder = folder.resolve(URLEncoder.encode(repoName, StandardCharsets.UTF_8));
        try {
            var localRepo = Repository.get(repoFolder)
                                      .or(() -> Optional.of(initialize(censusRepo, censusRef, repoFolder)))
                                      .orElseThrow();
            var hash = localRepo.fetch(censusRepo.url(), censusRef, false);
            localRepo.checkout(hash, true);
        } catch (IOException e) {
            initialize(censusRepo, censusRef, repoFolder);
        }

        try {
            var configuration = configuration(pr.repository(), pr.targetRef());
            var census = Census.parse(repoFolder);
            var project = project(configuration, census);
            var namespace = namespace(census, pr.repository().namespace());
            return new CensusInstance(census, configuration, project, namespace);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse census at " + repoFolder, e);
        }
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
}
