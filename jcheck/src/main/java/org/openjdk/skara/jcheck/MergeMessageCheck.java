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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.util.Iterator;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class MergeMessageCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.merge");
    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf) {
        if (!commit.isMerge()) {
            return iterator();
        }

        var pattern = Pattern.compile(conf.checks().merge().message());
        if (commit.message().size() != 1 || !pattern.matcher(commit.message().get(0)).matches()) {
            var metadata = CommitIssue.metadata(commit, message, conf, this);
            log.finer("issue: wrong merge message");
            return iterator(new MergeMessageIssue(pattern.toString(), metadata));
        }

        return iterator();
    }

    @Override
    public String name() {
        return "merge";
    }

    @Override
    public String description() {
        return "Merge commit must contain a proper message";
    }
}
