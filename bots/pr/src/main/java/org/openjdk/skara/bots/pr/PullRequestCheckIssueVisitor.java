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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.jcheck.Check;
import org.openjdk.skara.vcs.Hash;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class PullRequestCheckIssueVisitor implements IssueVisitor {
    private final Set<String> messages = new HashSet<>();
    private final List<CheckAnnotation> annotations = new LinkedList<>();
    private final Set<Check> enabledChecks;
    private final Set<Class<? extends Check>> failedChecks = new HashSet<>();

    private boolean readyForReview;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private final Set<Class<? extends Check>> displayedChecks = Set.of(
            DuplicateIssuesCheck.class,
            ReviewersCheck.class,
            WhitespaceCheck.class,
            IssuesCheck.class
    );

    PullRequestCheckIssueVisitor(Set<Check> enabledChecks) {
        this.enabledChecks = enabledChecks;
        readyForReview = true;
    }

    List<String> getMessages() {
        return new ArrayList<>(messages);
    }

    Map<String, Boolean> getChecks() {
        return enabledChecks.stream()
                            .filter(check -> displayedChecks.contains(check.getClass()))
                            .collect(Collectors.toMap(Check::description,
                                                      check -> !failedChecks.contains(check.getClass())));
    }

    List<CheckAnnotation> getAnnotations() { return annotations; }

    boolean isReadyForReview() {
        return readyForReview;
    }

    public void visit(DuplicateIssuesIssue e) {
        var id = e.issue().id();
        var other = e.hashes()
                     .stream()
                     .map(Hash::abbreviate)
                     .map(s -> "         - " + s)
                     .collect(Collectors.toList());

        var output = new StringBuilder();
        output.append("Issue id ").append(id).append(" is already used in these commits:\n");
        other.forEach(h -> output.append(" * ").append(h).append("\n"));
        messages.add(output.toString());
        failedChecks.add(e.check().getClass());
        readyForReview = false;
    }

    @Override
    public void visit(TagIssue e) {
        log.fine("ignored: illegal tag name: " + e.tag().name());
    }

    @Override
    public void visit(BranchIssue e) {
        log.fine("ignored: illegal branch name: " + e.branch().name());
    }

    @Override
    public void visit(SelfReviewIssue e)
    {
        messages.add("Self-reviews are not allowed");
        failedChecks.add(e.check().getClass());
        readyForReview = false;
    }

    @Override
    public void visit(TooFewReviewersIssue e) {
        messages.add(String.format("Too few reviewers with at least role %s found (have %d, need at least %d)", e.role(), e.numActual(), e.numRequired()));
        failedChecks.add(e.check().getClass());
    }

    @Override
    public void visit(InvalidReviewersIssue e) {
        var invalid = String.join(", ", e.invalid());
        throw new IllegalStateException("Invalid reviewers " + invalid);
    }

    @Override
    public void visit(MergeMessageIssue e) {
        var message = String.join("\n", e.commit().message());
        throw new IllegalStateException("Merge commit message is not " + e.expected() + ", but: " + message);
    }

    @Override
    public void visit(HgTagCommitIssue e) {
        throw new IllegalStateException("Hg tag commit issue - should not happen");
    }

    @Override
    public void visit(CommitterIssue e) {
        log.fine("ignored: invalid author: " + e.commit().author().name());
    }

    @Override
    public void visit(CommitterNameIssue issue) {
        log.fine("ignored: invalid committer name");
    }

    @Override
    public void visit(CommitterEmailIssue issue) {
        log.fine("ignored: invalid committer email");
    }

    @Override
    public void visit(AuthorNameIssue issue) {
        throw new IllegalStateException("Invalid author name: " + issue.commit().author());
    }

    @Override
    public void visit(AuthorEmailIssue issue) {
        throw new IllegalStateException("Invalid author email: " + issue.commit().author());
    }

    @Override
    public void visit(WhitespaceIssue e) {
        var startColumn = Integer.MAX_VALUE;
        var endColumn = Integer.MIN_VALUE;
        var details = new LinkedList<String>();
        for (var error : e.errors()) {
            startColumn = Math.min(error.index(), startColumn);
            endColumn = Math.max(error.index(), endColumn);
            details.add("Column " + error.index() + ": " + error.kind().toString());
        }

        var annotationBuilder = CheckAnnotationBuilder.create(
                e.path().toString(),
                e.row(),
                e.row(),
                CheckAnnotationLevel.FAILURE,
                String.join("  \n", details));

        if (startColumn < Integer.MAX_VALUE) {
            annotationBuilder.startColumn(startColumn);
        }
        if (endColumn > Integer.MIN_VALUE) {
            annotationBuilder.endColumn(endColumn);
        }

        var annotation = annotationBuilder.title("Whitespace error").build();
        annotations.add(annotation);

        messages.add("Whitespace errors");
        failedChecks.add(e.check().getClass());
        readyForReview = false;
    }

    @Override
    public void visit(MessageIssue issue) {
        var message = String.join("\n", issue.commit().message());
        throw new IllegalStateException("Incorrectly formatted commit message: " + message);
    }

    @Override
    public void visit(IssuesIssue issue) {
        messages.add("The commit message does not reference any issue. To add an issue reference to this PR, " +
                "edit the title to be of the format `issue number`: `message`.");
        failedChecks.add(issue.check().getClass());
        readyForReview = false;
    }

    @Override
    public void visit(ExecutableIssue issue) {
        messages.add(String.format("Executable files are not allowed (file: %s)", issue.path()));
        failedChecks.add(issue.check().getClass());
        readyForReview = false;
    }

    @Override
    public void visit(BlacklistIssue issue) {
        log.fine("ignored: blacklisted commit");
    }

    @Override
    public void visit(BinaryIssue issue) {
        log.fine("ignored: binary file");
    }
}
