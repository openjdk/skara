/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
    private final Map<Class<? extends Check>, List<String>> errorFailedChecks = new HashMap<>();
    private final Map<Class<? extends Check>, List<String>> warningFailedChecks = new HashMap<>();
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

    private void setNotReadyForReviewOnError(Severity severity) {
        if (severity == Severity.ERROR) {
            readyForReview = false;
        }
    }

    private void addMessage(Check check, String message, Severity severity) {
        if (severity == Severity.ERROR) {
            errorFailedChecks.computeIfAbsent(check.getClass(), k -> new ArrayList<>()).add(message);
        } else if (severity == Severity.WARNING) {
            warningFailedChecks.computeIfAbsent(check.getClass(), k -> new ArrayList<>()).add(message);
        }
    }

    List<String> errorFailedChecksMessages() {
        return errorFailedChecks.values().stream().flatMap(List::stream).toList();
    }


    boolean hasErrors(boolean reviewNeeded) {
        if (reviewNeeded) {
            return !errorFailedChecks.isEmpty();
        } else {
            return errorFailedChecks.keySet().stream()
                    .anyMatch(check -> !check.equals(ReviewersCheck.class));
        }
    }

    List<String> warningFailedChecksMessages() {
        return warningFailedChecks.values().stream().flatMap(List::stream).toList();
    }

    List<String> hiddenWarningMessages() {
        return warningFailedChecks.entrySet().stream()
                .filter(entry -> !displayedChecks.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .flatMap(List::stream)
                .sorted()
                .collect(Collectors.toList());
    }

    List<String> hiddenErrorMessages() {
        return errorFailedChecks.entrySet().stream()
                .filter(entry -> !displayedChecks.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .flatMap(List::stream)
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
                                                      check -> !errorFailedChecks.containsKey(check.getClass())));
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
                                                      check -> !errorFailedChecks.containsKey(check.getClass())));
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

    public void visit(DuplicateIssuesIssue issue) {
        var id = issue.issue().id();
        var other = issue.hashes()
                .stream()
                .map(Hash::abbreviate)
                .map(s -> "         - " + s)
                .toList();

        var output = new StringBuilder();
        output.append("Issue id ").append(id).append(" is already used in these commits:\n");
        other.forEach(h -> output.append(" * ").append(h).append("\n"));
        addMessage(issue.check(), output.toString(), issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(TagIssue issue) {
        log.fine("ignored: illegal tag name: " + issue.tag().name());
    }

    @Override
    public void visit(BranchIssue issue) {
        log.fine("ignored: illegal branch name: " + issue.branch().name());
    }

    @Override
    public void visit(SelfReviewIssue issue) {
        var message = issue.severity().equals(Severity.ERROR) ? "Self-reviews are not allowed" :
                "Self-reviews are not recommended";
        addMessage(issue.check(), message, issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(TooFewReviewersIssue issue) {
        addMessage(issue.check(), String.format("Too few reviewers with at least role %s found (have %d, need at least %d)",
                issue.role(), issue.numActual(), issue.numRequired()), issue.severity());
    }

    @Override
    public void visit(InvalidReviewersIssue issue) {
        var invalid = String.join(", ", issue.invalid());
        addMessage(issue.check(), "Invalid reviewers " + invalid, issue.severity());
    }

    @Override
    public void visit(MergeMessageIssue issue) {
        var message = String.join("\n", issue.commit().message());
        var desc = "Merge commit message is not `" + issue.expected() + "`, but:";
        if (issue.commit().message().size() == 1) {
            desc += " `" + message + "`";
        } else {
            desc += "\n" +
                    "```\n" +
                    message +
                    "```";
        }
        addMessage(issue.check(), desc, issue.severity());
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
        var message = issue.severity().equals(Severity.ERROR) ? "Pull request's HEAD commit must contain a full name" :
                "Pull request's HEAD commit doesn't contain a full name";
        addMessage(issue.check(), message, issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(AuthorEmailIssue issue) {
        // We only get here for contributors without an OpenJDK username
        var message = issue.severity().equals(Severity.ERROR) ? "Pull request's HEAD commit must contain a valid e-mail" :
                "Pull request's HEAD commit doesn't contain a valid e-mail";
        addMessage(issue.check(), message, issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(WhitespaceIssue issue) {
        var startColumn = Integer.MAX_VALUE;
        var endColumn = Integer.MIN_VALUE;
        var details = new LinkedList<String>();
        for (var error : issue.errors()) {
            startColumn = Math.min(error.index(), startColumn);
            endColumn = Math.max(error.index(), endColumn);
            details.add("Column " + error.index() + ": " + error.kind().toString());
        }

        var annotationBuilder = CheckAnnotationBuilder.create(
                issue.path().toString(),
                issue.row(),
                issue.row(),
                CheckAnnotationLevel.FAILURE,
                String.join("  \n", details));

        if (startColumn < Integer.MAX_VALUE) {
            annotationBuilder.startColumn(startColumn);
        }
        if (endColumn > Integer.MIN_VALUE) {
            annotationBuilder.endColumn(endColumn);
        }

        var annotation = annotationBuilder.title("Whitespace " + issue.severity().toString()).build();
        annotations.add(annotation);

        addMessage(issue.check(), "Whitespace " + issue.severity().toString() + "s", issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(MessageIssue issue) {
        var message = String.join("\n", issue.commit().message());
        log.warning("Incorrectly formatted commit message: " + message);
        addMessage(issue.check(), "Incorrectly formatted commit message", issue.severity());
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
        addMessage(issue.check(), "The commit message contains " + desc + " on line " + issue.line(), issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(IssuesIssue issue) {
        addMessage(issue.check(), "The commit message does not reference any issue. To add an issue reference to this PR, " +
                "edit the title to be of the format `issue number`: `message`.", issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(ExecutableIssue issue) {
        var message = issue.severity().equals(Severity.ERROR) ? String.format("Executable files are not allowed (file: %s)", issue.path())
                : String.format("Patch contains an executable file (%s)", issue.path());
        addMessage(issue.check(), message, issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(SymlinkIssue issue) {
        var message = issue.severity().equals(Severity.ERROR) ? String.format("Symbolic links are not allowed (file: %s)", issue.path())
                : String.format("Patch contains a symbolic link (%s)", issue.path());
        addMessage(issue.check(), message, issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(BinaryIssue issue) {
        var message = issue.severity().equals(Severity.ERROR) ? String.format("Binary files are not allowed (file: %s)", issue.path())
                : String.format("Patch contains a binary file (%s)", issue.path());
        addMessage(issue.check(), message, issue.severity());
        setNotReadyForReviewOnError(issue.severity());
    }

    @Override
    public void visit(ProblemListsIssue issue) {
        addMessage(issue.check(), issue.issue() + " is used in problem lists: " + issue.files(), issue.severity());
    }

    @Override
    public void visit(IssuesTitleIssue issue) {
        List<String> messages = new ArrayList<>();
        if (!issue.issuesWithTrailingPeriod().isEmpty()) {
            messages.add("Found trailing period in issue title for " + String.join(", ", issue.issuesWithTrailingPeriod()));
        }
        if (!issue.issuesWithLeadingLowerCaseLetter().isEmpty()) {
            messages.add("Found leading lowercase letter in issue title for " + String.join(", ", issue.issuesWithLeadingLowerCaseLetter()));
        }
        addMessage(issue.check(), String.join("\n", messages),
                issue.severity());
    }

    @Override
    public void visit(CopyrightFormatIssue issue) {
        List<String> messages = new ArrayList<>();
        for (var entry : issue.filesWithCopyrightFormatIssue().entrySet()) {
            messages.add("Found copyright format issue for " + entry.getKey() + " in [" + String.join(", ", entry.getValue()) + "]");
        }
        for (var entry : issue.filesWithCopyrightMissingIssue().entrySet()) {
            messages.add("Can't find copyright header for " + entry.getKey() + " in [" + String.join(", ", entry.getValue()) + "]");
        }
        addMessage(issue.check(), String.join("\n", messages),
                issue.severity());
    }
}
