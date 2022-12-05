/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.common;

import java.util.Optional;

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.jbs.JdkVersion;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.network.UncheckedRestException;

/**
 * This class contains utility methods used by more than one bot. These methods
 * can't reasonably be located in the various libraries as they combine
 * functionality and knowledge unique to bot applications. As this class grows,
 * it should be encouraged to split it up into more cohesive units.
 */
public class BotUtils {

    /**
     * Gets jcheck configured fix version from a pull request. This only works for
     * repositories where the fix version is configured in .jcheck/conf.
     */
    public static Optional<JdkVersion> getVersion(PullRequest pr) {
        String confFile;
        confFile = pr.repository().fileContents(".jcheck/conf", pr.headHash().hex())
                .orElse(pr.repository().fileContents(".jcheck/conf", pr.targetRef()).orElseThrow());
        var configuration = JCheckConfiguration.parse(confFile.lines().toList());
        var version = configuration.general().version().orElse(null);
        if (version == null || "".equals(version)) {
            return Optional.empty();
        }
        return JdkVersion.parse(version);
    }
}
