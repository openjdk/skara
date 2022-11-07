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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.jcheck.Check;
import org.openjdk.skara.vcs.Hash;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class PullRequestCheckIssueVisitor implements IssueVisitor {
    private final List<CheckAnnotation> annotations = new LinkedList<>();
    private final Set<Check> enabledChecks;
    private final Map<Class<? extends Check>, String> failedChecks = new HashMap<>();

    private boolean readyForReview;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private final Set<Class<? extends Check>> displayedChecks = Set.of(
            DuplicateIssuesCheck.class,
            ReviewersCheck.class,
            WhitespaceCheck.class,
            IssuesCheck.class
    );

    private JCheckConfiguration configuration;

    PullRequestCheckIssueVisitor(Set<Check> enabledChecks) {
        this.enabledChecks = enabledChecks;
        readyForReview = true;
    }

    private void addFailureMessage(Check check, String message) {
        failedChecks.put(check.getClass(), message);
    }

    List<String> messages() {
        return new ArrayList<>(failedChecks.values());
    }

    List<String> hiddenMessages() {
        return failedChecks.entrySet().stream()
                           .filter(entry -> !displayedChecks.contains(entry.getKey()))
                           .map(Map.Entry::getValue)
                           .sorted()
                           .collect(Collectors.toList());
    }

    /**
     * Get all the displayed checks with results.
     */
    Map<String, Boolean> getChecks() {
        return enabledChecks.stream()
                            .filter(check -> displayedChecks.contains(check.getClass()))
                            .collect(Collectors.toMap(this::checkDescription,
                                                      check -> !failedChecks.containsKey(check.getClass())));
    }

    /**
     * Get all the displayed checks with results that were used to decide if this change is ready for
     * review.
     */
    Map<String, Boolean> getReadyForReviewChecks() {
        return enabledChecks.stream()
                            .filter(check -> displayedChecks.contains(check.getClass()))
                            .filter(check -> !(check instanceof ReviewersCheck))
                            .collect(Collectors.toMap(this::checkDescription,
                                                      check -> !failedChecks.containsKey(check.getClass())));
    }

    private String checkDescription(Check check) {
        if (check instanceof ReviewersCheck && configuration != null) {
            return check.description() + " (" + configuration.checks().reviewers().getReviewRequirements() + ")";
        }
        return check.description();
    }

    List<CheckAnnotation> getAnnotations() { return annotations; }

    boolean isReadyForReview() {
        return readyForReview;
    }

    void setConfiguration(JCheckConfiguration configuration) {
        this.configuration = configuration;
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
        addFailureMessage(e.check(), output.toString());
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
        addFailureMessage(e.check(), "Self-reviews are not allowed");
        readyForReview = false;
    }

    @Override
    public void visit(TooFewReviewersIssue e) {
        addFailureMessage(e.check(), String.format("Too few reviewers with at least role %s found (have %d, need at least %d)", e.role(), e.numActual(), e.numRequired()));
    }

    @Override
    public void visit(InvalidReviewersIssue e) {
        var invalid = String.join(", ", e.invalid());
        addFailureMessage(e.check(), "Invalid reviewers " + invalid);
    }

    @Override
    public void visit(MergeMessageIssue e) {
        var message = String.join("\n", e.commit().message());
        var desc = "Merge commit message is not `" + e.expected() + "`, but:";
        if (e.commit().message().size() == 1) {
            desc += " `" + message + "`";
        } else {
            desc += "\n" +
                    "```\n" +
                    message +
                    "```";
        }
        addFailureMessage(e.check(), desc);
    }

    @Override
    public void visit(HgTagCommitIssue e) {
        log.fine("ignored: invalid tag commit");
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
        // We only get here for contributors without an OpenJDK username
        addFailureMessage(issue.check(), "Pull request's HEAD commit must contain a full name");
        readyForReview = false;
    }

    @Override
    public void visit(AuthorEmailIssue issue) {
        // We only get here for contributors without an OpenJDK username
        addFailureMessage(issue.check(), "Pull request's HEAD commit must contain a valid e-mail");
        readyForReview = false;
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

        addFailureMessage(e.check(), "Whitespace errors");
        readyForReview = false;
    }

    @Override
    public void visit(MessageIssue issue) {
        var message = String.join("\n", issue.commit().message());
        log.warning("Incorrectly formatted commit message: " + message);
        addFailureMessage(issue.check(), "Incorrectly formatted commit message");
    }

    @Override
    public void visit(MessageWhitespaceIssue issue) {
        String desc;
        if (issue.kind() == MessageWhitespaceIssue.Whitespace.TRAILING) {
            desc = "trailing whitespace";
        } else if (issue.kind() == MessageWhitespaceIssue.Whitespace.CR) {
            desc = "a carriage return";
        } else if (issue.kind() == MessageWhitespaceIssue.Whitespace.TAB) {
            desc = "a tab";
        } else {
            desc = "an unknown kind of whitespace (" + issue.kind().name() + ")";
        }
        addFailureMessage(issue.check(), "The commit message contains " + desc + " on line " + issue.line());
        readyForReview = false;
    }

    @Override
    public void visit(IssuesIssue issue) {
        addFailureMessage(issue.check(), "The commit message does not reference any issue. To add an issue reference to this PR, " +
                "edit the title to be of the format `issue number`: `message`.");
        readyForReview = false;
    }

    @Override
    public void visit(ExecutableIssue issue) {
        addFailureMessage(issue.check(), String.format("Executable files are not allowed (file: %s)", issue.path()));
        readyForReview = false;
    }

    @Override
    public void visit(SymlinkIssue issue) {
        addFailureMessage(issue.check(), String.format("Symbolic links are not allowed (file: %s)", issue.path()));
        readyForReview = false;
    }

    @Override
    public void visit(BinaryIssue issue) {
        addFailureMessage(issue.check(), String.format("Binary files are not allowed (file: %s)", issue.path()));
        readyForReview = false;
    }

    @Override
    public void visit(ProblemListsIssue issue) {
        addFailureMessage(issue.check(), issue.issue() + " is used in problem lists: " + issue.files());
    }

    @Override
    public void visit(JCheckConfIssue issue) {
        addFailureMessage(issue.check(), ".jcheck/conf is invalid: " + issue.getErrorMessage());
        readyForReview = false;
    }
}
