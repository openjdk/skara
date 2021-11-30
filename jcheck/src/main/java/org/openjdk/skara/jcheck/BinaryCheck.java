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
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class BinaryCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.binary");

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf, Census census) {
        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var fileSizeLimits = conf.checks().binary().fileSizeLimits();

        var issues = new ArrayList<Issue>();
        for (var diff : commit.parentDiffs()) {
            for (var patch : diff.patches()) {
                if (patch.isTextual()) {
                    continue;
                }
                // Here, the file is surely binary.
                var binaryPatch = (BinaryPatch) patch;
                var path =  binaryPatch.target().path().orElse(null);
                var fileName = path != null ? path.getFileName().toString() : "";

                long fileSize = 0;
                boolean needCheck = false;

                if (binaryPatch.status().isAdded()) {
                    // This is a new-added file, so the BinaryHunk#inflatedSize of the first hunk should be the file size.
                    fileSize = binaryPatch.hunks().get(0).inflatedSize();
                    needCheck = true;
                } else if (binaryPatch.status().isRenamed() || binaryPatch.status().isCopied()
                        || binaryPatch.status().isModified() || binaryPatch.status().isUnmerged()) {
                    // Use the file size in the file system.
                    try {
                        fileSize = path != null ? Files.size(path) : 0;
                        needCheck = true;
                    } catch (IOException e) {
                        log.warning("The file '" + path + "' doesn't exist. ");
                    }
                }

                // If the size of the binary file exceeds the limited size, the check should fail.
                if (needCheck) {
                    var excessSize = calculateExcessSize(fileSizeLimits, fileName, fileSize);
                    if (excessSize > 0) {
                        var limitedSize = fileSize - excessSize;
                        log.info("The size of the binary file `" + path + "` is " + fileSize
                                 + (fileSize > 1 ? " Bytes" : " Byte") + ", which is larger than the limited file size: "
                                 + limitedSize + (limitedSize > 1 ? " Bytes." : " Byte."));
                        issues.add(new BinaryFileTooLargeIssue(path, fileSize, limitedSize, metadata));
                    }
                }
            }
        }
        return issues.iterator();
    }

    /**
     * Check whether the file size is exceeded and return the excess size.
     * @return the value of (the file size - the limited size)
     */
    private long calculateExcessSize(Map<Pattern, Long> fileSizeLimits, String fileName, long fileSize) {
        for (var entry : fileSizeLimits.entrySet()) {
            if (entry.getKey().matcher(fileName).matches()) {
                return fileSize - entry.getValue();
            }
        }
        // the `fileSizeLimits` has no related limits about this file.
        return 0;
    }

    @Override
    public String name() {
        return "binary";
    }

    @Override
    public String description() {
        return "Files should not be binary";
    }
}
