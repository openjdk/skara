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
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.vcs.openjdk.Issue;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.logging.Logger;

public class DuplicateIssuesCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.duplicate-issues");
    private final ReadOnlyRepository repo;
    private Map<String, List<Hash>> issuesToHashes = null;

    DuplicateIssuesCheck(ReadOnlyRepository repo) {
        this.repo = repo;
    }

    private void populateIssuesToHashesMap() throws IOException {
        issuesToHashes = new HashMap<String, List<Hash>>();

        for (var metadata : repo.commitMetadata()) {
            for (var line : metadata.message()) {
                var issue = Issue.fromString(line);
                if (issue.isPresent()) {
                    var id = issue.get().id();
                    if (!issuesToHashes.containsKey(id)) {
                        issuesToHashes.put(id, new ArrayList<Hash>());
                    }
                    issuesToHashes.get(id).add(metadata.hash());
                }
            }
        }
    }

    @Override
    Iterator<org.openjdk.skara.jcheck.Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf) {
        try {
            if (issuesToHashes == null) {
                populateIssuesToHashesMap();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var issues = new ArrayList<org.openjdk.skara.jcheck.Issue>();
        for (var issue : message.issues()) {
            var hashes = issuesToHashes.get(issue.id());
            if (hashes != null && hashes.size() > 1) {
                log.finer("issue: the JBS issue " + issue.toString() + " has been used in multiple commits");
                issues.add(new DuplicateIssuesIssue(issue, hashes, metadata));
            }
        }
        return issues.iterator();
    }

    @Override
    public String name() {
        return "duplicate-issues";
    }

    @Override
    public String description() {
        return "Referenced JBS issue must only be used for a single change";
    }
}
