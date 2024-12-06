/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.jcheck.iterators.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParser;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.net.URI;
import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;
import java.util.logging.Logger;

public class JCheck {
    private final ReadOnlyRepository repository;
    private final CommitMessageParser parser;
    private final String revisionRange;
    private final List<CommitCheck> commitChecks;
    private final static List<CommitCheck> commitChecksForStagedOrWorkingTree = List.of(
            new AuthorCheck(),
            new CommitterCheck(),
            new WhitespaceCheck(),
            new ExecutableCheck(),
            new SymlinkCheck(),
            new BinaryCheck()
    );
    private final List<RepositoryCheck> repositoryChecks;
    private final List<String> additionalConfiguration;
    private final JCheckConfiguration overridingConfiguration;
    private final Census overridingCensus;
    private final Map<URI, Census> censuses = new HashMap<>();
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck");

    public final static String WORKING_TREE_REV = "SKARA_GIT_WORKING_TREE_AS_REV";

    public final static String STAGED_REV = "SKARA_GIT_STAGED_AS_REV";

    JCheck(ReadOnlyRepository repository,
           CommitMessageParser parser,
           String revisionRange,
           Pattern allowedBranches,
           Pattern allowedTags,
           List<String> additionalConfiguration,
           JCheckConfiguration overridingConfiguration,
           Census overridingCensus) throws IOException {
        this.repository = repository;
        this.parser = parser;
        this.revisionRange = revisionRange;
        this.additionalConfiguration = additionalConfiguration;
        this.overridingConfiguration = overridingConfiguration;
        this.overridingCensus = overridingCensus;

        var utils = new Utilities();
        commitChecks = List.of(
            new AuthorCheck(),
            new CommitterCheck(),
            new WhitespaceCheck(),
            new MergeMessageCheck(),
            new HgTagCommitCheck(utils),
            new DuplicateIssuesCheck(repository),
            new ReviewersCheck(utils),
            new MessageCheck(utils),
            new IssuesCheck(utils),
            new ExecutableCheck(),
            new SymlinkCheck(),
            new BinaryCheck(),
            new ProblemListsCheck(repository),
            new IssuesTitleCheck(),
            new CopyrightFormatCheck(repository)
        );
        repositoryChecks = List.of(
            new BranchesCheck(allowedBranches),
            new TagsCheck(allowedTags)
        );
    }

    public static Optional<JCheckConfiguration> parseConfiguration(List<String> configuration, List<String> additionalConfiguration) {
        var content = new ArrayList<>(configuration);
        content.addAll(additionalConfiguration);
        if (content.size() == 0) {
            return Optional.empty();
        }
        return Optional.of(JCheckConfiguration.parse(content));
    }

    public static Optional<JCheckConfiguration> parseConfiguration(ReadOnlyRepository r, Hash h, List<String> additionalConfiguration) {
        try {
            var content = new ArrayList<>(r.lines(Paths.get(".jcheck/conf"), h).orElse(Collections.emptyList()));
            return parseConfiguration(content, additionalConfiguration);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<JCheckConfiguration> getConfigurationFor(Commit c) {
        if (overridingConfiguration != null) {
            return Optional.of(overridingConfiguration);
        }
        return parseConfiguration(repository, c.hash(), additionalConfiguration);
    }

    private Iterator<Issue> checkCommit(Commit commit) {
        log.fine("Checking: " + commit.hash().hex());
        var configuration = getConfigurationFor(commit);
        if (!configuration.isPresent()) {
            log.finer("No .jcheck/conf present for " + commit.hash().hex());
            return Collections.emptyIterator();
        }

        var conf = configuration.get();
        var census = overridingCensus;
        if (census == null) {
            var uri = conf.census().url();
            if (!censuses.containsKey(uri)) {
                try {
                    censuses.put(uri, Census.from(uri));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            census = censuses.get(uri);
        }
        var finalCensus = census;
        var message = parser.parse(commit);
        var availableChecks = (revisionRange.equals(STAGED_REV) || revisionRange.equals(WORKING_TREE_REV)) ? commitChecksForStagedOrWorkingTree : commitChecks;
        var enabled = conf.checks().enabled(availableChecks);
        var iterator = new MapIterator<>(enabled.iterator(), c -> {
            log.finer("Running commit check '" + c.name() + "' for " + commit.hash().hex());
            return c.check(commit, message, conf, finalCensus);
        });
        return new FlatMapIterator<>(iterator);
    }

    private Set<CommitCheck> checksForCommit(Commit c) {
        var configuration = getConfigurationFor(c);
        if (!configuration.isPresent()) {
            return new HashSet<>();
        }

        var conf = configuration.get();
        return new HashSet<>(conf.checks().enabled(commitChecks));
    }

    private Set<Check> checksForRange() throws IOException {
        if (overridingConfiguration != null) {
            return new HashSet<>(overridingConfiguration.checks().enabled(commitChecks));
        }
        try (var commits = repository.commits(revisionRange)) {
            return commits.stream()
                          .flatMap(commit -> checksForCommit(commit).stream())
                          .collect(Collectors.toSet());
        }
    }

    public static class Issues implements Iterable<Issue>, AutoCloseable {
        private final Iterator<Issue> iterator;
        private final Closeable resource;

        public Issues(Iterator<Issue> iterator,
                      Closeable resource) {
            this.iterator = iterator;
            this.resource = resource;
        }

        @Override
        public Iterator<Issue> iterator() {
            return iterator;
        }

        public List<Issue> asList() {
            var res = new ArrayList<Issue>();
            for (var err : this) {
                res.add(err);
            }
            return res;
        }

        public Stream<Issue> stream() {
            return StreamSupport.stream(spliterator(), false);
        }

        @Override
        public void close() throws IOException {
            if (resource != null) {
                resource.close();
            }
        }
    }

    private Iterator<Issue> commitIssues(Commits commits) {
        return new FlatMapIterator<>(
                new MapIterator<>(commits.iterator(), this::checkCommit));
    }

    private Iterator<Issue> repositoryIssues() {
        var iterator = new MapIterator<>(repositoryChecks.iterator(), c -> {
            log.finer("Running repository check '" + c.name() + "'");
            return c.check(repository);
        });
        return new FlatMapIterator<>(iterator);
    }

    private Issues issues() throws IOException {
        var commits = repository.commits(revisionRange);

        var repositoryIssues = repositoryIssues();
        Iterator<Issue> commitIssues;
        if (revisionRange.equals(STAGED_REV)) {
            commitIssues = checkCommit(repository.staged());
        } else if (revisionRange.equals(WORKING_TREE_REV)) {
            commitIssues = checkCommit(repository.workingTree());
        } else {
            commitIssues = commitIssues(commits);
        }

        var errors = new ConcatIterator<>(repositoryIssues, commitIssues);
        return new Issues(errors, commits);
    }

    private static Issues check(ReadOnlyRepository repository,
                                CommitMessageParser parser,
                                String branchRegex,
                                String tagRegex,
                                String revisionRange,
                                List<String> additionalConfiguration,
                                JCheckConfiguration configuration,
                                Census census) throws IOException {

        var defaultBranchRegex = "|" + repository.defaultBranch().name();
        var allowedBranches = Pattern.compile("^(?:" + branchRegex + defaultBranchRegex + ")$");

        var defaultTag = repository.defaultTag();
        var defaultTagRegex = defaultTag.isPresent() ? "|" + defaultTag.get().name() : "";
        var allowedTags = Pattern.compile("^(?:" + tagRegex + defaultTagRegex + ")$");

        var jcheck = new JCheck(repository, parser, revisionRange, allowedBranches, allowedTags, additionalConfiguration, configuration, census);
        return jcheck.issues();
    }

    public static Issues check(ReadOnlyRepository repository,
                               Census census,
                               CommitMessageParser parser,
                               Hash toCheck,
                               JCheckConfiguration configuration) throws IOException {
        if (repository.isEmpty()) {
            return new Issues(new ArrayList<Issue>().iterator(), null);
        }

        var branchRegex = configuration.repository().branches();
        var tagRegex = configuration.repository().tags();

        return check(repository, parser, branchRegex, tagRegex, repository.range(toCheck), List.of(), configuration, census);
    }

    public static Issues check(ReadOnlyRepository repository,
                               Census census,
                               CommitMessageParser parser,
                               String revisionRange,
                               JCheckConfiguration overridingConfig) throws IOException {
        if (repository.isEmpty()) {
            return new Issues(new ArrayList<Issue>().iterator(), null);
        }

        var defaultHead = repository.resolve(repository.defaultBranch().name());
        var head = repository.head();

        var conf = defaultHead.isPresent() ?
            parseConfiguration(repository, defaultHead.get(), List.of()) :
            parseConfiguration(repository, head, List.of());
        var branchRegex = conf.isPresent() ? conf.get().repository().branches() : ".*";
        var tagRegex = conf.isPresent() ? conf.get().repository().tags() : ".*";

        return check(repository, parser, branchRegex, tagRegex, revisionRange, List.of(), overridingConfig, census);
    }

    public static Set<Check> checksFor(ReadOnlyRepository repository, Hash hash) throws IOException {
        var jcheck = new JCheck(repository,
                                CommitMessageParsers.v1,
                                repository.range(hash),
                                Pattern.compile(".*"),
                                Pattern.compile(".*"),
                                List.of(),
                                null,
                                null);
        return jcheck.checksForRange();
    }

    public static Set<Check> checksFor(ReadOnlyRepository repository, JCheckConfiguration conf) throws IOException {
        var jcheck = new JCheck(repository,
                                CommitMessageParsers.v1,
                                null,
                                Pattern.compile(".*"),
                                Pattern.compile(".*"),
                                List.of(),
                                conf,
                                null);
        return jcheck.checksForRange();
    }

    public static List<String> commitCheckNamesForStagedOrWorkingTree() {
        return commitChecksForStagedOrWorkingTree.stream()
                .map(Check::name)
                .toList();
    }
}
