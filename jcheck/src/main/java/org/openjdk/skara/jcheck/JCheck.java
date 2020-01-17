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

import org.openjdk.skara.census.Census;
import org.openjdk.skara.jcheck.iterators.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParser;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;
import java.util.logging.Logger;

public class JCheck {
    private final ReadOnlyRepository repository;
    private final Census census;
    private final CommitMessageParser parser;
    private final String revisionRange;
    private final Map<String, Set<Hash>> whitelist;
    private final List<CommitCheck> commitChecks;
    private final List<RepositoryCheck> repositoryChecks;
    private final List<String> additionalConfiguration;
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck");

    private JCheckConfiguration cachedConfiguration = null;

    JCheck(ReadOnlyRepository repository,
           Census census,
           CommitMessageParser parser,
           String revisionRange,
           Pattern allowedBranches,
           Pattern allowedTags,
           Map<String, Set<Hash>> whitelist,
           Set<Hash> blacklist,
           List<String> additionalConfiguration) throws IOException {
        this.repository = repository;
        this.census = census;
        this.parser = parser;
        this.revisionRange = revisionRange;
        this.whitelist = whitelist;
        this.additionalConfiguration = additionalConfiguration;

        var utils = new Utilities();
        commitChecks = List.of(
            new AuthorCheck(),
            new CommitterCheck(census),
            new WhitespaceCheck(),
            new MergeMessageCheck(),
            new HgTagCommitCheck(utils),
            new DuplicateIssuesCheck(repository),
            new ReviewersCheck(census, utils),
            new MessageCheck(utils),
            new IssuesCheck(utils),
            new ExecutableCheck(),
            new BlacklistCheck(blacklist)
        );
        repositoryChecks = List.of(
            new BranchesCheck(allowedBranches),
            new TagsCheck(allowedTags)
        );
    }

    private static Optional<JCheckConfiguration> parseConfiguration(ReadOnlyRepository r, Hash h, List<String> additionalConfiguration) {
        try {
            var content = new ArrayList<>(r.lines(Paths.get(".jcheck/conf"), h).orElse(Collections.emptyList()));
            content.addAll(additionalConfiguration);
            if (content.size() == 0) {
                return Optional.empty();
            }
            return Optional.of(JCheckConfiguration.parse(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<JCheckConfiguration> getConfigurationFor(Commit c) {
        var confPath = Paths.get(".jcheck/conf");
        var changesConfiguration = c.parentDiffs()
                                    .stream()
                                    .map(Diff::patches)
                                    .flatMap(List::stream)
                                    .anyMatch(p -> p.source().path().isPresent() && p.source().path().get().equals(confPath) ||
                                                   p.target().path().isPresent() && p.target().path().get().equals(confPath));


        if (changesConfiguration || cachedConfiguration == null) {
            var confAtCommit = parseConfiguration(repository, c.hash(), additionalConfiguration);
            confAtCommit.ifPresent(jCheckConfiguration -> cachedConfiguration = jCheckConfiguration);
            return confAtCommit;
        } else {
            return Optional.of(cachedConfiguration);
        }
    }

    private Iterator<Issue> checkCommit(Commit commit) {
        log.fine("Checking: " + commit.hash().hex());
        var configuration = getConfigurationFor(commit);
        if (!configuration.isPresent()) {
            log.finer("No .jcheck/conf present for " + commit.hash().hex());
            return Collections.emptyIterator();
        }

        var conf = configuration.get();
        var message = parser.parse(commit);
        var enabled = conf.checks().enabled(commitChecks);
        var iterator = new MapIterator<CommitCheck, Iterator<Issue>>(enabled.iterator(), c -> {
            var skip = whitelist.get(c.name());
            if (skip != null && skip.contains(commit.hash())) {
                log.finer("Commit check '" + c.name() + "' is whitelisted for " + commit.hash().hex());
                return Collections.emptyIterator();
            }
            log.finer("Running commit check '" + c.name() + "' for " + commit.hash().hex());
            return c.check(commit, message, conf);
        });
        return new FlatMapIterator<Issue>(iterator);
    }

    private Set<CommitCheck> checksForCommit(Commit c) {
        var configuration = getConfigurationFor(c);
        if (!configuration.isPresent()) {
            return new HashSet<>();
        }

        var conf = configuration.get();
        return new HashSet<>(conf.checks().enabled(commitChecks));
    }

    private Set<Check> checksForCommits() throws IOException {
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
        return new FlatMapIterator<Issue>(
                new MapIterator<Commit, Iterator<Issue>>(commits.iterator(), this::checkCommit));
    }

    private Iterator<Issue> repositoryIssues() {
        var iterator = new MapIterator<RepositoryCheck, Iterator<Issue>>(repositoryChecks.iterator(), c -> {
            log.finer("Running repository check '" + c.name() + "'");
            return c.check(repository);
        });
        return new FlatMapIterator<Issue>(iterator);
    }

    private Issues issues() throws IOException {
        var commits = repository.commits(revisionRange);

        var repositoryIssues = repositoryIssues();
        var commitIssues = commitIssues(commits);

        var errors = new ConcatIterator<Issue>(repositoryIssues, commitIssues);
        return new Issues(errors, commits);
    }

    private static Issues check(ReadOnlyRepository repository,
                                Census census,
                                CommitMessageParser parser,
                                String branchRegex,
                                String tagRegex,
                                String revisionRange,
                                Map<String, Set<Hash>> whitelist,
                                Set<Hash> blacklist,
                                List<String> additionalConfiguration) throws IOException {

        var defaultBranchRegex = "|" + repository.defaultBranch().name();
        var allowedBranches = Pattern.compile("^(?:" + branchRegex + defaultBranchRegex + ")$");

        var defaultTag = repository.defaultTag();
        var defaultTagRegex = defaultTag.isPresent() ? "|" + defaultTag.get().name() : "";
        var allowedTags = Pattern.compile("^(?:" + tagRegex + defaultTagRegex + ")$");

        var jcheck = new JCheck(repository, census, parser, revisionRange, allowedBranches, allowedTags, whitelist, blacklist, additionalConfiguration);
        return jcheck.issues();
    }

    public static Issues check(ReadOnlyRepository repository,
                               Census census,
                               CommitMessageParser parser,
                               String revisionRange,
                               Hash configuration,
                               Map<String, Set<Hash>> whitelist,
                               Set<Hash> blacklist,
                               List<String> additionalConfiguration) throws IOException {
        if (repository.isEmpty()) {
            return new Issues(new ArrayList<Issue>().iterator(), null);
        }

        var conf = parseConfiguration(repository, configuration, additionalConfiguration);

        var branchRegex = conf.isPresent() ?  conf.get().repository().branches() : ".*";
        var tagRegex =  conf.isPresent() ?  conf.get().repository().tags() : ".*";

        return check(repository, census, parser, branchRegex, tagRegex, revisionRange, whitelist, blacklist, additionalConfiguration);
    }

    public static Issues check(ReadOnlyRepository repository,
                               Census census,
                               CommitMessageParser parser,
                               String revisionRange,
                               Hash configuration,
                               Map<String, Set<Hash>> whitelist,
                               Set<Hash> blacklist) throws IOException {
        return check(repository, census, parser, revisionRange, configuration, whitelist, blacklist, List.of());
    }

    public static Issues check(ReadOnlyRepository repository,
                               Census census,
                               CommitMessageParser parser,
                               String revisionRange) throws IOException {
        var master = repository.resolve(repository.defaultBranch().name())
                               .orElseThrow(() -> new IllegalStateException("Default branch not found"));
        var whitelist = new HashMap<String, Set<Hash>>();
        var blacklist = new HashSet<Hash>();
        return check(repository, census, parser, revisionRange, master, whitelist, blacklist);
    }

    public static Issues check(ReadOnlyRepository repository,
                               Census census,
                               CommitMessageParser parser,
                               String revisionRange,
                               Map<String, Set<Hash>> whitelist,
                               Set<Hash> blacklist) throws IOException {
        var master = repository.resolve(repository.defaultBranch().name())
                               .orElseThrow(() -> new IllegalStateException("Default branch not found"));
        return check(repository, census, parser, revisionRange, master, whitelist, blacklist);
    }

    public static Set<Check> checks(ReadOnlyRepository repository, Census census, Hash hash) throws IOException {
        var jcheck = new JCheck(repository,
                                census,
                                CommitMessageParsers.v1,
                                hash.hex() + "^.." + hash.hex(),
                                Pattern.compile(".*"),
                                Pattern.compile(".*"),
                                new HashMap<String, Set<Hash>>(),
                                new HashSet<Hash>(),
                                List.of());
        return jcheck.checksForCommits();
    }
}
