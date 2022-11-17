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

import java.nio.file.Path;
import java.util.Optional;

class CensusInstance extends LimitedCensusInstance {
    private final Project project;

    private CensusInstance(Census census, JCheckConfiguration configuration, Project project, Namespace namespace) {
        super(census, configuration, namespace);
        this.project = project;
    }

    private static Project project(JCheckConfiguration configuration, Census census) {
        var project = census.project(configuration.general().project());

        if (project == null) {
            throw new RuntimeException("Project not found in census: " + configuration.general().project());
        }

        return project;
    }

    static CensusInstance createCensusInstance(HostedRepositoryPool hostedRepositoryPool,
                                 HostedRepository censusRepo, String censusRef, Path folder, PullRequest pr,
                                 HostedRepository confOverrideRepo, String confOverrideName, String confOverrideRef) throws InvalidJCheckConfException, MissingJCheckConfException {
        return createCensusInstance(hostedRepositoryPool, censusRepo, censusRef, folder, pr.repository(), pr.targetRef(),
                      confOverrideRepo, confOverrideName, confOverrideRef);
    }

    static CensusInstance createCensusInstance(HostedRepositoryPool hostedRepositoryPool,
                                 HostedRepository censusRepo, String censusRef, Path folder, HostedRepository repository, String ref,
                                 HostedRepository confOverrideRepo, String confOverrideName, String confOverrideRef) throws InvalidJCheckConfException, MissingJCheckConfException {
        var limitedCensusInstance = LimitedCensusInstance.createLimitedCensusInstance(hostedRepositoryPool, censusRepo,
                censusRef, folder, repository, ref, confOverrideRepo, confOverrideName, confOverrideRef);
        return new CensusInstance(limitedCensusInstance.census, limitedCensusInstance.configuration,
                project(limitedCensusInstance.configuration, limitedCensusInstance.census), limitedCensusInstance.namespace);
    }

    Project project() {
        return project;
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

