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

import org.openjdk.skara.census.Census;
import org.openjdk.skara.vcs.Author;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.CommitMetadata;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.io.IOException;

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
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(new Hash("12345789012345789012345678901234567890"));
        var authored = ZonedDateTime.now();
        var message = new ArrayList<String>();
        message.addAll(List.of("Initial commit"));
        if (!reviewers.isEmpty()) {
            message.addAll(List.of("", "Reviewed-by: " + String.join(", ", reviewers)));
        }
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, message);
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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));
        assertEquals(0, issues.size());
    }

    @Test
    void leadAsReviewerShouldPass() throws IOException {
        var commit = commit(List.of("foo"));
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));
        assertEquals(0, issues.size());
    }

    @Test
    void committerAsReviewerShouldFail() throws IOException {
        var commit = commit(List.of("baz"));
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

        assertEquals(0, issues.size());
    }

    @Test
    void oneReviewerAndUknownReviewerShouldFail() throws IOException {
        var commit = commit(List.of("bar", "unknown"));
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(0)));

        assertEquals(0, issues.size());
    }

    @Test
    void selfReviewShouldNotPass() throws IOException {
        var commit = commit(new Author("bar", "bar@localhost"), List.of("bar"));
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1, ignored)));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TooFewReviewersIssue);
    }

    @Test
    void requiringCommitterAndReviwerShouldPass() throws IOException {
        var commit = commit(List.of("bar", "baz"));
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1, 1)));

        assertEquals(0, issues.size());
    }

    @Test
    void missingRoleShouldFail() throws IOException {
        var commit = commit(List.of("bar", "qux"));
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(1, 1)));

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
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(0, 1, 1)));

        assertEquals(0, issues.size());
    }

    @Test
    void relaxedRoleAndMissingRoleShouldFail() throws IOException {
        var commit = commit(List.of("bar", "contributor"));
        var check = new ReviewersCheck(census(), utils);
        var issues = toList(check.check(commit, message(commit), conf(0, 1, 1)));

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
        var check = new ReviewersCheck(census(), utils);
        var legacyConf = new ArrayList<>(CONFIGURATION);
        legacyConf.add("minimum = 1");
        legacyConf.add("role = reviewer");
        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf)));
        assertEquals(0, issues.size());
    }

    @Test
    void legacyConfigurationShouldAcceptRole() throws IOException {
        var commit = commit(List.of("baz"));
        var check = new ReviewersCheck(census(), utils);
        var legacyConf = new ArrayList<>(CONFIGURATION);
        legacyConf.add("minimum = 1");
        legacyConf.add("role = reviewer");
        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf)));

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
        var check = new ReviewersCheck(census(), utils);
        var legacyConf = new ArrayList<>(CONFIGURATION);
        legacyConf.add("minimum = 1");
        legacyConf.add("role = committer");

        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("bar"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("baz"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("qux"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(legacyConf)));
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
        var check = new ReviewersCheck(census(), utils);
        var modernConf = new ArrayList<>(CONFIGURATION);
        modernConf.add("committers = 1");

        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("bar"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("baz"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("qux"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(modernConf)));
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
        var check = new ReviewersCheck(census(), utils);
        var oldJDKConf = new ArrayList<String>();
        oldJDKConf.add("project=jdk");
        oldJDKConf.add("bugids=dup");

        var issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("bar"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("baz"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("qux"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of("contributor"));
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf)));
        assertEquals(0, issues.size());

        commit = commit(List.of());
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf)));
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
        issues = toList(check.check(commit, message(commit), JCheckConfiguration.parse(oldJDKConf)));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof InvalidReviewersIssue);
        var invalidIssue = (InvalidReviewersIssue) issues.get(0);
        assertEquals(List.of("unknown"), invalidIssue.invalid());
        assertEquals(commit, invalidIssue.commit());
        assertEquals(Severity.ERROR, invalidIssue.severity());
        assertEquals(check, invalidIssue.check());
    }
}
