/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.census.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

class CensusInstance {
    private final Namespace namespace;

    private CensusInstance(Namespace namespace) {
        this.namespace = namespace;
    }

    private static Repository initialize(HostedRepository repo, String ref, Path folder) {
        try {
            return Repository.materialize(folder, repo.url(), "+" + ref + ":" + "issue_census_" + repo.name());
        } catch (IOException e) {
            throw new RuntimeException("Failed to retrieve census to " + folder, e);
        }
    }

    private static Namespace namespace(Census census, String hostNamespace) {
        var namespace = census.namespace(hostNamespace);
        if (namespace == null) {
            throw new RuntimeException("Namespace not found in census: " + hostNamespace);
        }

        return namespace;
    }

    static CensusInstance create(HostedRepository censusRepo, String censusRef, Path folder, String namespace) {
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
            var census = Census.parse(repoFolder);
            var ns = namespace(census, namespace);
            return new CensusInstance(ns);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse census at " + repoFolder, e);
        }
    }

    Namespace namespace() {
        return namespace;
    }
}
