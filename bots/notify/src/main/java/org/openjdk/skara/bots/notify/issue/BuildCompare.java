/*
 * Copyright (c) 20202, Oracle and/or its affiliates. All rights reserved.
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

import java.util.regex.Pattern;

public class BuildCompare {
    private static final Pattern buildPattern = Pattern.compile("^b(\\d+)");

    // Return the number from a numbered build (e.g., 'b12' -> 12), or -1 if not a numbered build.
    private static int buildNumber(String build) {
        var buildMatcher = buildPattern.matcher(build);
        if (buildMatcher.matches()) {
            return Integer.parseInt(buildMatcher.group(1));
        } else {
            return -1;
        }
    }

    // Notable values for rib are 'team', 'master', and numbered builds
    // (b22).  'team' should not overwrite any value; 'master' should only
    // overwrite 'team'; numbered builds (b22) should only be overwritten by
    // lower numbered builds.
    // The last condition is due to the use of duplicate bugids in jdk update
    // releases.  A fix could be made in jdk7u10-b02, and also (due to an
    // escalation or some other urgent need) be fixed in jdk7u8-b04.  At some
    // later date when jdk7u8 is merged into, say, jdk7u10-b10, without the
    // last condition the Resolved in Build field would be changed from b02
    // to b10.
    public static boolean shouldReplace(String newBuild, String oldBuild) {
        if (oldBuild == null) {
            return true;
        }
        if (newBuild.equals(oldBuild)) {
            return false;
        }
        if (newBuild.equals("team")) {
            return false;
        }
        if (newBuild.startsWith("ma")) {
            return oldBuild.equals("team");
        }

        var oldBuildNumber = buildNumber(oldBuild);
        var newBuildNumber = buildNumber(newBuild);

        return oldBuildNumber < 0 || (newBuildNumber >= 0 && newBuildNumber < oldBuildNumber);
    }
}
