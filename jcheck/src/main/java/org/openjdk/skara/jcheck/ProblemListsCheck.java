/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.FileEntry;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ProblemListsCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.problemlists");
    private static final Pattern WHITESPACES = Pattern.compile("\\s+");

    private final ReadOnlyRepository repo;

    ProblemListsCheck(ReadOnlyRepository repo) {
        this.repo = repo;
    }

    private Stream<String> getProblemListedIssues(Path path, Hash hash){
        try {
            var lines = repo.lines(path, hash);
            if (lines.isEmpty()) {
                return Stream.empty();
            }
            return lines.get()
                        .stream()
                        .map(String::trim)
                        .filter(s -> !s.startsWith("#"))
                        .map(WHITESPACES::split)
                        .filter(ss -> ss.length > 1)
                        .map(ss -> ss[1])
                        .flatMap(s -> Arrays.stream(s.split(",")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf) {
        var problemListed = new HashMap<String, List<Path>>();
        var checkConf = conf.checks().problemlists();
        var dirs = checkConf.dirs();
        var pattern = Pattern.compile(checkConf.pattern()).asMatchPredicate();
        try {
            var hash = commit.hash();
            for (var dir : dirs.split("\\|")) {
                repo.files(hash, Path.of(dir))
                    .stream()
                    .map(FileEntry::path)
                    .filter(p -> pattern.test(p.getFileName().toString()))
                    .forEach(p -> getProblemListedIssues(p, commit.hash()).forEach(t -> problemListed.compute(t,
                             (k, v) -> {if (v == null) v = new ArrayList<>(); v.add(p); return v;})));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var issues = new ArrayList<Issue>();
        for (var issue : message.issues()) {
            var problemLists = problemListed.get(issue.id());
            if (problemLists != null) {
                log.finer(String.format("issue: %s is found in problem lists: %s", issue.id(), problemLists));
                issues.add(new ProblemListsIssue(metadata, issue.id(), new HashSet<>(problemLists)));
            }
        }

        return issues.iterator();
    }

    @Override
    public String name() {
        return "problemlists";
    }

    @Override
    public String description() {
        return "Fixed issue should not be in a problem list";
    }
}
