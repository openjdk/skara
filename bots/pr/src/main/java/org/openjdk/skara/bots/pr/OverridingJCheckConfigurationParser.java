/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.jcheck.JCheck;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.vcs.Hash;

import java.util.*;

class OverridingJCheckConfigurationParser {
    private final Repository localRepo;
    private final HostedRepository hostedRepo;

    private final List<String> overridingConf;

    OverridingJCheckConfigurationParser(Repository localRepo, Optional<HostedRepository> overridingRepository,
                                      String overridingConfName, String overridingRef) {
        this(localRepo, null, overridingRepository, overridingConfName, overridingRef);
    }

    OverridingJCheckConfigurationParser(HostedRepository hostedRepo, Optional<HostedRepository> overridingRepository,
                                      String overridingConfName, String overridingRef) {
        this(null, hostedRepo, overridingRepository, overridingConfName, overridingRef);
    }

    private OverridingJCheckConfigurationParser(Repository localRepo, HostedRepository hostedRepo,
                                              Optional<HostedRepository> overridingRepository,
                                              String overridingConfName, String overridingRef) {
        if (localRepo == null && hostedRepo == null) {
            throw new IllegalArgumentException("One of localRepo and hostedRepo must be non-null");
        }
        if (localRepo != null && hostedRepo != null) {
            throw new IllegalArgumentException("Only one of localRepo and hostedRepo can be non-null");
        }

        this.localRepo = localRepo;
        this.hostedRepo = hostedRepo;

        if (overridingRepository.isPresent()) {
            overridingConf =
                overridingRepository.get().fileContents(overridingConfName, overridingRef).orElseThrow(
                    () -> new RuntimeException("Could not find " + overridingConfName + " on ref " +
                                               overridingRef + " in repo " +
                                               overridingRepository.get().name())
            ).lines().toList();
        } else {
            overridingConf = List.of();
        }
    }

    private Optional<JCheckConfiguration> parseLocalOrHosted(Hash hash, List<String> additions) {
        if (localRepo != null) {
            return JCheck.parseConfiguration(localRepo, hash, additions);
        }

        var hostedConf = hostedRepo.fileContents(".jcheck/conf", hash.hex());
        if (hostedConf.isEmpty()) {
            return Optional.empty();
        }

        return JCheck.parseConfiguration(hostedConf.get().lines().toList(), additions);
    }

    Optional<JCheckConfiguration> parse(Hash hash) {
        return parseWithAdditions(hash, List.of());

    }

    Optional<JCheckConfiguration> parseWithAdditions(Hash hash, List<String> additions) {
        return overridingConf.isEmpty() ?
            parseLocalOrHosted(hash, additions) : JCheck.parseConfiguration(overridingConf, additions);

    }
}
