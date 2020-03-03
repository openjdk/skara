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
package org.openjdk.skara.cli;

import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.vcs.Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class JCheckCLIVisitor implements IssueVisitor {
    private final Set<String> ignore;
    private boolean hasDisplayedErrors;

    public JCheckCLIVisitor() {
        this(Set.of());
    }

    public JCheckCLIVisitor(Set<String> ignore) {
        this.ignore = ignore;
        this.hasDisplayedErrors = false;
    }

    private String println(Issue i, String message) {
        var prefix = "[" + i.check().name() + "] " + i.severity() + ": ";
        System.out.print(prefix);
        System.out.println(message);
        return prefix;
    }

    private String println(CommitIssue i, String message) {
        var prefix = "[" + i.check().name() + "] " + i.severity() + ": " +
                     i.commit().hash().abbreviate() + ": ";
        System.out.print(prefix);
        System.out.println(message);
        return prefix;
    }

    public void visit(DuplicateIssuesIssue i) {
        if (!ignore.contains(i.check().name())) {
            var id = i.issue().id();
            var hash = i.commit().hash().abbreviate();
            var other = i.hashes()
                         .stream()
                         .map(Hash::abbreviate)
                         .map(s -> "         - " + s)
                         .collect(Collectors.toList());
            println(i, "issue id '" + id + "' in commit " + hash + " is already used in commits:");
            other.forEach(System.out::println);
            hasDisplayedErrors = true;
        }
    }

    public void visit(TagIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "illegal tag name: " + i.tag().name());
            hasDisplayedErrors = true;
        }
    }

    public void visit(BranchIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "illegal branch name: " + i.branch().name());
            hasDisplayedErrors = true;
        }
    }

    public void visit(SelfReviewIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "self-reviews are not allowed");
            hasDisplayedErrors = true;
        }
    }

    public void visit(TooFewReviewersIssue i) {
        if (!ignore.contains(i.check().name())) {
            var required = i.numRequired();
            var actual = i.numActual();
            var reviewers = required == 1 ? " reviewer" : " reviewers";
            println(i, required + reviewers + " required, found " + actual);
            hasDisplayedErrors = true;
        }
    }

    public void visit(InvalidReviewersIssue i) {
        if (!ignore.contains(i.check().name())) {
            var invalid = String.join(", ", i.invalid());
            var wording = i.invalid().size() == 1 ? " is" : " are";
            println(i, invalid + wording + " not part of OpenJDK");
            hasDisplayedErrors = true;
        }
    }

    public void visit(MergeMessageIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "merge commits should only use the commit message '" + i.expected() + "'");
            hasDisplayedErrors = true;
        }
    }

    public void visit(HgTagCommitIssue i) {
        if (!ignore.contains(i.check().name())) {
            hasDisplayedErrors = true;
            switch (i.error()) {
                case TOO_MANY_LINES:
                    println(i, "message should only be one line");
                    return;
                case BAD_FORMAT:
                    println(i, "message should be of format 'Added tag <tag> for changeset <hash>'");
                    return;
                case TOO_MANY_CHANGES:
                    println(i, "should only add one line to .hgtags");
                    return;
                case TAG_DIFFERS:
                    println(i, "tag differs in commit message and .hgtags");
                    return;
            }
        }
    }

    public void visit(CommitterIssue i) {
        if (!ignore.contains(i.check().name())) {
            var committer = i.commit().committer().name();
            var project = i.project().name();
            println(i, committer + " is not committer in project " + project);
            hasDisplayedErrors = true;
        }
    }

    private static class WhitespaceRange {
        private final WhitespaceIssue.Whitespace kind;
        private final int start;
        private final int end;

        public WhitespaceRange(WhitespaceIssue.Whitespace kind, int start, int end) {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }

        public WhitespaceIssue.Whitespace kind() {
            return kind;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }
    }

    private static List<WhitespaceRange> ranges(List<WhitespaceIssue.Error> errors) {
        if (errors.size() == 1) {
            var res = new ArrayList<WhitespaceRange>();
            res.add(new WhitespaceRange(errors.get(0).kind(), errors.get(0).index(), errors.get(0).index()));
            return res;
        }

        var merged = new ArrayList<WhitespaceRange>();
        var start = errors.get(0);
        var end = start;
        for (int i = 1; i < errors.size(); i++) {
            var e = errors.get(i);
            if (e.index() == (end.index() + 1) && e.kind() == end.kind()) {
                end = e;
            } else {
                merged.add(new WhitespaceRange(e.kind(), start.index(), end.index()));
                start = e;
            }
        }

        return merged;
    }

    public void visit(WhitespaceIssue i) {
        if (!ignore.contains(i.check().name())) {
            var pos = i.path() + ":" + i.row();
            var prefix = println(i, i.describe() + " in " + pos);
            var indent = prefix.replaceAll(".", " ");
            System.out.println(indent + i.escapeLine());
            System.out.println(indent + i.hints());
            hasDisplayedErrors = true;
        }
    }

    public void visit(MessageIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "contains additional lines in commit message");
            for (var line : i.message().additional()) {
                System.out.println("> " + line);
            }
            hasDisplayedErrors = true;
        }
    }

    public void visit(MessageWhitespaceIssue i) {
        if (!ignore.contains(i.check().name())) {
            String desc = null;
            if (i.kind().isTab()) {
                desc = "tab";
            } else if (i.kind().isCR()) {
                desc = "carriage-return";
            } else {
                desc = "trailing whitespace";
            }
            println(i, "contains " + desc + " on line " + i.line() + " in commit message:");
            System.out.println("> " + i.commit().message().get(i.line() - 1));
            hasDisplayedErrors = true;
        }
    }

    public void visit(IssuesIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "missing reference to JBS issue in commit message");
            for (var line : i.commit().message()) {
                System.out.println("> " + line);
            }
            hasDisplayedErrors = true;
        }
    }

    public void visit(ExecutableIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "file " + i.path() + " is executable");
            hasDisplayedErrors = true;
        }
    }

    public void visit(AuthorNameIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "missing author name");
            hasDisplayedErrors = true;
        }
    }

    public void visit(AuthorEmailIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "missing author email");
            hasDisplayedErrors = true;
        }
    }

    public void visit(CommitterNameIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "missing committer name");
            hasDisplayedErrors = true;
        }
    }

    public void visit(CommitterEmailIssue i) {
        if (!ignore.contains(i.check().name())) {
            var domain = i.expectedDomain();
            println(i, "missing committer email from domain " + domain);
            hasDisplayedErrors = true;
        }
    }

    public void visit(BlacklistIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "commit is blacklisted");
            hasDisplayedErrors = true;
        }
    }

    public void visit(BinaryIssue i) {
        if (!ignore.contains(i.check().name())) {
            println(i, "adds binary file: " + i.path().toString());
            hasDisplayedErrors = true;
        }
    }

    public boolean hasDisplayedErrors() {
        return hasDisplayedErrors;
    }
}
