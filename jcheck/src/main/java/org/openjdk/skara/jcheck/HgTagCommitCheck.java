/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.util.regex.Pattern;
import java.util.Iterator;
import java.util.logging.Logger;

public class HgTagCommitCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.hg-tag");
    private final Utilities utils;

    HgTagCommitCheck(Utilities utils) {
        this.utils = utils;
    }

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf) {
        if (commit.isMerge() || !utils.addsHgTag(commit)) {
            return iterator();
        }
        var metadata = CommitIssue.metadata(commit, message, conf, this);

        if (commit.message().size() != 1) {
            log.finer("issue: too many lines in commit message");
            return iterator(HgTagCommitIssue.tooManyLines(metadata));
        }

        var line = commit.message().get(0);
        var pattern = Pattern.compile("Added tag (" + conf.repository().tags() + ") for changeset ([a-zA-Z0-9]+)$");
        var matcher = pattern.matcher(line);
        if (!matcher.matches()) {
            log.finer("issue: commit message has wrong format");
            return iterator(HgTagCommitIssue.badFormat(metadata));
        }

        var diff = commit.parentDiffs().get(0);
        var patches = diff.patches();
        if (patches.size() != 1) {
            log.finer("issue: too many patches");
            return iterator(HgTagCommitIssue.tooManyChanges(metadata));
        }

        var patch = patches.get(0);
        if (!patch.target().path().isPresent() ||
            !(patch.target().path().get().toString().endsWith(".hgtags") || patch.target().path().get().toString().endsWith(".hgtags-top-repo"))) {
            log.severe("utils.addsHgTag returned true but commit does change .hgtags");
            throw new IllegalArgumentException("commit " + commit.hash() + " does not add a tag");
        }

        if (patch.isBinary()) {
            throw new IllegalArgumentException("commit " + commit.hash() + " contains binary patch to .hgtags");
        }

        var hunks = patch.asTextualPatch().hunks();
        if (hunks.size() != 1) {
            log.finer("issue: too many hunks");
            return iterator(HgTagCommitIssue.tooManyChanges(metadata));
        }

        var hunk = hunks.get(0);
        var lines = hunk.target().lines();
        if (lines.size() != 1) {
            log.finer("issue: too many lines");
            return iterator(HgTagCommitIssue.tooManyChanges(metadata));
        }

        var words = lines.get(0).split("\\s");
        var fileTag = words[1];
        var messageTag = matcher.group(1);
        if (!messageTag.equals(fileTag)) {
            log.finer("issue: different tag in commit message and .hgtags");
            return iterator(HgTagCommitIssue.tagDiffers(metadata));
        }

        // Can't check that the hash of the tag matches here, there are too
        // many tag commits from before the consolidation whose messages
        // weren't updated to reflect the new hash for the tag.

        return iterator();
    }

    @Override
    public String name() {
        return "hg-tag";
    }

    @Override
    public String description() {
        return "Change must contain correct Mercurial tags";
    }
}
