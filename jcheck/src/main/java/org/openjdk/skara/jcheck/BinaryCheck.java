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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.census.Census;
import org.openjdk.skara.vcs.BinaryPatch;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.logging.Logger;

public class BinaryCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.binary");

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf, Census census) {
        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var maxSize = conf.checks().binary().maxSize();

        var issues = new ArrayList<Issue>();
        for (var diff : commit.parentDiffs()) {
            for (var patch : diff.patches()) {
                if (patch.isTextual()) {
                    // Excluded the textual patch.
                    continue;
                }
                if (patch.status().isDeleted()) {
                    // Excluded the deleted file.
                    continue;
                }
                if (maxSize <= 0) {
                    // If the maxSize is not set or is set to 0, any binary file can't be added.
                    issues.add(new BinaryIssue(patch.target().path().get(), metadata));
                }
                if (patch.status().isRenamed() || patch.status().isCopied()) {
                    // Excluded the renamed and copied files now. Note that the moved file is identified as renamed.
                    // It should be seperated from the deleted file, because the deleted file don't need
                    // to be checked, but the renamed file should be checked for the `BinaryIssue`.
                    continue;
                }
                if (maxSize > 0) {
                    long fileSize = 0;
                    var binaryPatch = (BinaryPatch) patch;
                    for (var hunk : binaryPatch.hunks()) {
                        fileSize += hunk.inflatedSize();
                    }
                    if (fileSize > maxSize) {
                        var path = binaryPatch.target().path().isPresent() ?
                                binaryPatch.target().path().get() : binaryPatch.source().path().get();
                        issues.add(new BinaryFileTooLargeIssue(path, fileSize, maxSize, metadata));
                    }
                }
            }
        }
        return issues.iterator();
    }

    @Override
    public String name() {
        return "binary";
    }

    @Override
    public String description() {
        return "Binary files don't meet the requirement.";
    }
}
