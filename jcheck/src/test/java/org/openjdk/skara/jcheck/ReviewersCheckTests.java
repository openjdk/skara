/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.Author;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.CommitMetadata;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.vcs.openjdk.CommitMessageFormatters;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.io.IOException;

import static org.openjdk.skara.jcheck.ReviewersConfiguration.BYLAWS_URL;

class ReviewersCheckTests {
    private final Utilities utils = new Utilities();

    private static final List<String> CENSUS = List.of(
        "<?xml version=\"1.0\" encoding=\"us-ascii\"?>",
        "<census time=\"2019-03-13T10:29:41-07:00\">",
        "  <person name=\"foo\">",
        "    <full-name>Foo</full-name>",
        "  </person>",
        "  <person name=\"bar\">",
        "    <full-name>Bar</full-name>",
        "  </person>",
        "  <person name=\"baz\">",
        "    <full-name>Baz</full-name>",
        "  </person>",
        "  <person name=\"qux\">",
        "    <full-name>Qux</full-name>",
        "  </person>",
        "  <person name=\"contributor\">",
        "    <full-name>Contributor</full-name>",
        "  </person>",
        "  <group name=\"test\">",
        "    <full-name>Test</full-name>",
        "    <person ref=\"foo\" role=\"lead\" />",
        "    <person ref=\"bar\" />",
        "    <person ref=\"baz\" />",
        "    <person ref=\"qux\" />",
        "  </group>",
        "  <project name=\"test\">",
        "    <full-name>Test</full-name>",
        "    <sponsor ref=\"test\" />",
        "    <person role=\"lead\" ref=\"foo\" />",
        "    <person role=\"reviewer\" ref=\"bar\" />",
        "    <person role=\"committer\" ref=\"baz\" />",
        "    <person role=\"author\" ref=\"qux\" />",
        "  </project>",
        "  <project name=\"jdk\">",
        "    <full-name>TestJDK</full-name>",
        "    <sponsor ref=\"test\" />",
        "    <person role=\"lead\" ref=\"foo\" />",
        "    <person role=\"reviewer\" ref=\"bar\" />",
        "    <person role=\"committer\" ref=\"baz\" />",
        "    <person role=\"author\" ref=\"qux\" />",
        "  </project>",
        "</census>"
    );

    private static final List<String> CONFIGURATION = List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = reviewers",
        "[checks \"reviewers\"]"
    );

    private static Commit commit(List<String> reviewers) {
        return commit(new Author("user", "user@host.org"), reviewers);
    }

    private static Commit commit(Author author, List<String> reviewers) {
        return commit(author, reviewers, null);
    }

    private static Commit commit(Author author, List<String> reviewers, Hash original) {
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(new Hash("12345789012345789012345678901234567890"));
        var authored = ZonedDateTime.now();

        var message = CommitMessage.title("Initial commit");
        message.reviewers(reviewers);
        if (original != null) {
            message.original(original);
        }
        var desc = message.format(CommitMessageFormatters.v1);
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, desc);
        return new Commit(metadata, List.of());
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    private static Census census() throws IOException {
        return Census.parse(CENSUS);
    }

    private static JCheckConfiguration conf() {
        return conf(1);
    }

    private static JCheckConfiguration conf(int reviewers) {
        return conf(reviewers, 0, 0);
    }

    private static JCheckConfiguration conf(int reviewers, List<String> ignored) {
        return conf(reviewers, 0, 0, ignored);
    }

    private static JCheckConfiguration conf(int reviewers, int committers) {
        return conf(reviewers, committers, 0);
    }

    private static JCheckConfiguration conf(int reviewers, int committers, int authors) {
        return conf(reviewers, committers, authors, List.of());
    }

    private static JCheckConfiguration conf(int reviewers, int committers, int authors, List<String> ignored) {
        var lines = new ArrayList<String>(CONFIGURATION);
        lines.add("reviewers = " + reviewers);
        lines.add("committers = " + committers);
        lines.add("authors = " + authors);
        lines.add("ignore = " + String.join(", ", ignored));
        return JCheckConfiguration.parse(lines);
    }

    private List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    @Test
    void singleReviewerShouldPass() throws IOException {
        var commit = commit(List.of("bar"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));
        assertEquals(0, issues.size());
    }

    @Test
    void leadAsReviewerShouldPass() throws IOException {
        var commit = commit(List.of("foo"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));
        assertEquals(0, issues.size());
    }

    @Test
    void committerAsReviewerShouldFail() throws IOException {
        var commit = commit(List.of("baz"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("reviewer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void authorAsReviewerShouldFail() throws IOException {
        var commit = commit(List.of("qux"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("reviewer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void noReviewersShouldFail() throws IOException {
        var commit = commit(List.of());
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("reviewer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void multipleInvalidReviewersShouldFail() throws IOException {
        var commit = commit(List.of("qux", "baz"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("reviewer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void uknownReviewersShouldFail() throws IOException {
        var commit = commit(List.of("unknown", "user"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof InvalidReviewersIssue);
        var issue = (InvalidReviewersIssue) issues.get(0);
        assertEquals(List.of("unknown", "user"), issue.invalid());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void oneReviewerAndMultipleInvalidReviewersShouldPass() throws IOException {
        var commit = commit(List.of("bar", "baz", "qux"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(0, issues.size());
    }

    @Test
    void oneReviewerAndUknownReviewerShouldFail() throws IOException {
        var commit = commit(List.of("bar", "unknown"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof InvalidReviewersIssue);
        var issue = (InvalidReviewersIssue) issues.get(0);
        assertEquals(List.of("unknown"), issue.invalid());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void zeroReviewersConfigurationShouldPass() throws IOException {
        var commit = commit(new ArrayList<String>());
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(0), census()));

        assertEquals(0, issues.size());
    }

    @Test
    void selfReviewShouldNotPass() throws IOException {
        var commit = commit(new Author("bar", "bar@localhost"), List.of("bar"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof SelfReviewIssue);
        var issue = (SelfReviewIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void ignoredReviewersShouldBeExcluded() throws IOException {
        var ignored = List.of("foo", "bar");
        var commit = commit(ignored);
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1, ignored), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
    }

    @Test
    void requiringCommitterAndReviwerShouldPass() throws IOException {
        var commit = commit(List.of("bar", "baz"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1, 1), census()));

        assertEquals(0, issues.size());
    }

    @Test
    void missingRoleShouldFail() throws IOException {
        var commit = commit(List.of("bar", "qux"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1, 1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("committer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void relaxedRoleShouldPass() throws IOException {
        var commit = commit(List.of("bar", "qux"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(0, 1, 1), census()));

        assertEquals(0, issues.size());
    }

    @Test
    void relaxedRoleAndMissingRoleShouldFail() throws IOException {
        var commit = commit(List.of("bar", "contributor"));
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(0, 1, 1), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("author", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void legacyConfigurationShouldWork() throws IOException {
        var commit = commit(List.of("bar"));
        var check = new ReviewersCheck(utils);
        var legacyConf = new ArrayList<>(CONFIGURATION);
        legacyConf.add("minimum = 1");
        legacyConf.add("role = reviewer");
        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf), census()));
        assertEquals(0, issues.size());
    }

    @Test
    void legacyConfigurationShouldAcceptRole() throws IOException {
        var commit = commit(List.of("baz"));
        var check = new ReviewersCheck(utils);
        var legacyConf = new ArrayList<>(CONFIGURATION);
        legacyConf.add("minimum = 1");
        legacyConf.add("role = reviewer");
        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("reviewer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void legacyConfigurationShouldAcceptCommitterRole() throws IOException {
        var commit = commit(List.of("foo"));
        var check = new ReviewersCheck(utils);
        var legacyConf = new ArrayList<>(CONFIGURATION);
        legacyConf.add("minimum = 1");
        legacyConf.add("role = committer");

        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("bar"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("baz"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("qux"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf), census()));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("committer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void modernConfigurationShouldAcceptCommitterRole() throws IOException {
        var commit = commit(List.of("foo"));
        var check = new ReviewersCheck(utils);
        var modernConf = new ArrayList<>(CONFIGURATION);
        modernConf.add("committers = 1");

        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("bar"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("baz"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("qux"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf), census()));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("committer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void oldJDKConfigurationShouldRequireContributor() throws IOException {
        var commit = commit(List.of("foo"));
        var check = new ReviewersCheck(utils);
        var oldJDKConf = new ArrayList<String>();
        oldJDKConf.add("project=jdk");
        oldJDKConf.add("bugids=dup");

        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("bar"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("baz"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("qux"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of("contributor"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf), census()));
        assertEquals(0, issues.size());

        commit = commit(List.of());
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf), census()));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("contributor", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());

        commit = commit(List.of("unknown"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf), census()));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof InvalidReviewersIssue);
        var invalidIssue = (InvalidReviewersIssue) issues.get(0);
        assertEquals(List.of("unknown"), invalidIssue.invalid());
        assertEquals(commit, invalidIssue.commit());
        assertEquals(Severity.ERROR, invalidIssue.severity());
        assertEquals(check, invalidIssue.check());
    }

    @Test
    void backportCommitWithoutReviewersIsFine() throws IOException {
        var original = new Hash("0123456789012345678901234567890123456789");
        var commit = commit(new Author("user", "user@host.org"), List.of(), original);
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf(1), census()));
        assertEquals(List.of(), issues);
    }

    @Test
    void backportCommitWithoutReviewersWithIgnoredCheckIsFine() throws IOException {
        var conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("backports = ignore");
        var original = new Hash("0123456789012345678901234567890123456789");
        var commit = commit(new Author("user", "user@host.org"), List.of(), original);
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(conf), census()));
        assertEquals(List.of(), issues);
    }

    @Test
    void backportCommitWithoutReviewersWithStrictCheckingIsError() throws IOException {
        var conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("backports = check");
        var original = new Hash("0123456789012345678901234567890123456789");
        var commit = commit(new Author("user", "user@host.org"), List.of(), original);
        var check = new ReviewersCheck(utils);
        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(conf), census()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
        var issue = (TooFewReviewersIssue) issues.get(0);
        assertEquals(0, issue.numActual());
        assertEquals(1, issue.numRequired());
        assertEquals("reviewer", issue.role());
        assertEquals(commit, issue.commit());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check, issue.check());
    }

    @Test
    void testReviewRequirements() throws IOException {
        var conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 0");
        assertEquals(constructReviewRequirement(0, 0, 0, 0, 0), JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        // one review required.
        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        assertEquals(constructReviewRequirement(0, 1, 0, 0, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("committers = 1");
        assertEquals(constructReviewRequirement(0, 0, 1, 0, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        // two reviews required.
        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("committers = 1");
        assertEquals(constructReviewRequirement(0, 1, 1, 0, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 2");
        assertEquals(constructReviewRequirement(0, 2, 0, 0, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        // three reviews required.
        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("committers = 1");
        conf.add("authors = 1");
        assertEquals(constructReviewRequirement(0, 1, 1, 1, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("committers = 2");
        assertEquals(constructReviewRequirement(0, 1, 2, 0, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("committers = 3");
        assertEquals(constructReviewRequirement(0, 0, 3, 0, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        // four reviews required.
        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("committers = 1");
        conf.add("authors = 1");
        conf.add("contributors = 1");
        assertEquals(constructReviewRequirement(0, 1, 1, 1, 1),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("committers = 1");
        conf.add("authors = 2");
        assertEquals(constructReviewRequirement(0, 1, 1, 2, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("authors = 3");
        assertEquals(constructReviewRequirement(0, 1, 0, 3, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("authors = 4");
        assertEquals(constructReviewRequirement(0, 0, 0, 4, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        // five reviews required.
        conf = new ArrayList<>(CONFIGURATION);
        conf.add("lead = 1");
        conf.add("reviewers = 1");
        conf.add("committers = 1");
        conf.add("authors = 1");
        conf.add("contributors = 1");
        assertEquals(constructReviewRequirement(1, 1, 1, 1, 1),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("committers = 1");
        conf.add("authors = 1");
        conf.add("contributors = 2");
        assertEquals(constructReviewRequirement(0, 1, 1, 1, 2),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("committers = 1");
        conf.add("contributors = 3");
        assertEquals(constructReviewRequirement(0, 1, 1, 0, 3),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("contributors = 4");
        assertEquals(constructReviewRequirement(0, 1, 0, 0, 4),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());

        conf = new ArrayList<>(CONFIGURATION);
        conf.add("contributors = 5");
        assertEquals(constructReviewRequirement(0, 0, 0, 0, 5),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());
    }

    private String constructReviewRequirement(int leadNum, int reviewerNum, int committerNum, int authorNum, int contributorNum) {
        // no review required.
        var noReview = "no review required";
        // review required template.
        var hasReview = "%d review%s required, with at least %s";
        var totalNum = leadNum + reviewerNum + committerNum + authorNum + contributorNum;
        if (totalNum == 0) {
            return noReview;
        }
        var requireList = new ArrayList<String>();
        var reviewRequirementMap = new LinkedHashMap<String, Integer>();
        reviewRequirementMap.put("[Lead%s](%s#project-lead)", leadNum);
        reviewRequirementMap.put("[Reviewer%s](%s#reviewer)", reviewerNum);
        reviewRequirementMap.put("[Committer%s](%s#committer)", committerNum);
        reviewRequirementMap.put("[Author%s](%s#author)", authorNum);
        reviewRequirementMap.put("[Contributor%s](%s#contributor)", contributorNum);
        for (var reviewRequirement : reviewRequirementMap.entrySet()) {
            var requirementNum = reviewRequirement.getValue();
            if (requirementNum > 0) {
                requireList.add(requirementNum+ " " + String.format(reviewRequirement.getKey(), requirementNum > 1 ? "s" : "", BYLAWS_URL));
            }
        }
        return String.format(hasReview, totalNum, totalNum > 1 ? "s" : "", String.join(", ", requireList));
    }

    @Test
    void minimumCanBeDisabled() {
        var conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("minimum = disable");
        assertEquals(constructReviewRequirement(0, 1, 0, 0, 0),
                JCheckConfiguration.parse(conf).checks().reviewers().getReviewRequirements());
    }

    @Test
    void minimumWithAnotherRoleTrows() {
        var conf = new ArrayList<>(CONFIGURATION);
        conf.add("reviewers = 1");
        conf.add("minimum = 1");
        assertThrows(IllegalStateException.class, () -> JCheckConfiguration.parse(conf));
    }

}
