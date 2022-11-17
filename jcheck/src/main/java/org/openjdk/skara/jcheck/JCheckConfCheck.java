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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.census.Census;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

public class JCheckConfCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.jcheckconf");

    private final ReadOnlyRepository repo;

    public JCheckConfCheck(ReadOnlyRepository repo) {
        this.repo = repo;
    }

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf, Census census) {
        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var hash = commit.hash();

        Optional<List<String>> lines;
        try {
            lines = repo.lines(Path.of(".jcheck/conf"), hash);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (lines.isEmpty()) {
            log.finer(".jcheck/conf is missing");
            return iterator(new JCheckConfIssue(metadata, ".jcheck/conf is missing"));
        }
        try {
            JCheckConfiguration.parse(lines.get());
        } catch (RuntimeException e) {
            log.finer(".jcheck/conf is not valid");
            return iterator(new JCheckConfIssue(metadata, e.getMessage()));
        }
        return iterator();
    }

    @Override
    public String name() {
        return "jcheckconf";
    }

    @Override
    public String description() {
        return "Change must contain valid jcheck configuration";
    }
}
