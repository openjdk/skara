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
package org.openjdk.skara.cli.pr;

import org.openjdk.skara.args.*;
import org.openjdk.skara.cli.ForgeUtils;
import org.openjdk.skara.cli.Logging;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.regex.Matcher;

class Utils {
    static final Pattern ISSUE_ID_PATTERN = Pattern.compile("([A-Za-z][A-Za-z0-9]+)?-([0-9]+)");
    static final Pattern ISSUE_MARKDOWN_PATTERN =
        Pattern.compile("^(?: \\* )?\\[([A-Z]+-[0-9]+)\\]\\(https:\\/\\/bugs.openjdk.(?:java.net)|(?:org)\\/browse\\/[A-Z]+-[0-9]+\\): .*$");

    static void exit(String fmt, Object...args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    static String getOption(String name, String subsection, Arguments arguments) {
        return ForgeUtils.getOption(name, "pr", subsection, arguments);
    }

    static boolean getSwitch(String name, String subsection, Arguments arguments) {
        return ForgeUtils.getSwitch(name, "pr", subsection, arguments);
    }

    static String rightPad(String s, int length) {
        return String.format("%-" + length + "s", s);
    }

    static void appendPaddedHTMLComment(Path file, String line) throws IOException {
        var end = " -->";
        var pad = 79 - end.length();
        var newLine = "\n";
        Files.writeString(file, rightPad("<!-- " + line, pad) + end + newLine, StandardOpenOption.APPEND);
    }

    static String format(Issue issue) {
        var parts = issue.id().split("-");
        var id = parts.length == 2 ? parts[1] : issue.id();
        return id + ": " + issue.title();
    }

    static String pullRequestIdArgument(ReadOnlyRepository repo, Arguments arguments) throws IOException {
        if (arguments.at(0).isPresent()) {
            return arguments.at(0).asString();
        }

        var currentBranch = repo.currentBranch();
        if (currentBranch.isPresent()) {
            var lines = repo.config("pr." + currentBranch.get().name() + ".id");
            if (lines.size() == 1) {
                return lines.get(0);
            }
        }

        exit("error: you must provide a pull request id");
        return null;
    }

    static String statusForPullRequest(PullRequest pr) {
        var labels = pr.labelNames();
        if (pr.isDraft()) {
            return "DRAFT";
        } else if (labels.contains("integrated")) {
            return "INTEGRATED";
        } else if (labels.contains("ready")) {
            return "READY";
        } else if (labels.contains("rfr")) {
            return "RFR";
        } else if (labels.contains("merge-conflict")) {
            return "CONFLICT";
        } else if (labels.contains("oca")) {
            return "OCA";
        } else {
            var checks = pr.checks(pr.headHash());
            var jcheck = Optional.ofNullable(checks.get("jcheck"));
            if (jcheck.isPresent()) {
                var checkStatus = jcheck.get().status();
                if (checkStatus == CheckStatus.IN_PROGRESS) {
                    return "CHECKING";
                } else if (checkStatus == CheckStatus.SUCCESS) {
                    return "RFR";
                } else if (checkStatus == CheckStatus.FAILURE) {
                    return "FAILURE";
                }
            } else {
                return "CHECKING";
            }
        }

        return "UNKNOWN";
    }

    static String statusForCheck(Check check) {
        var checkStatus = check.status();
        if (checkStatus == CheckStatus.IN_PROGRESS) {
            return "RUNNING";
        } else if (checkStatus == CheckStatus.SUCCESS) {
            return "OK";
        } else if (checkStatus == CheckStatus.FAILURE) {
            return "FAILED";
        } else if (checkStatus == CheckStatus.CANCELLED) {
            return "CANCELLED";
        } else if (checkStatus == CheckStatus.STALE) {
            return "STALE";
        }

        return "UNKNOWN";
    }

    static List<String> issuesFromPullRequest(PullRequest pr) {
        var issueTitleIndex = -1;
        var lines = pr.body().split("\n");
        for (var i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("### Issue")) {
                issueTitleIndex = i;
                break;
            }
        }

        if (issueTitleIndex == -1) {
            return List.of();
        }

        var issues = new ArrayList<String>();
        for (var i = issueTitleIndex + 1; i < lines.length; i++) {
            var m = ISSUE_MARKDOWN_PATTERN.matcher(lines[i]);
            if (m.matches()) {
                issues.add(m.group(1));
            } else {
                break;
            }
        }

        return issues;
    }

    static Optional<String> jbsProjectFromJcheckConf(Repository repo, String targetBranch) throws IOException {
        var conf = JCheckConfiguration.from(repo, repo.resolve(targetBranch).orElseThrow(() ->
            new IOException("Could not resolve '" + targetBranch + "' branch")
        ));

        if (conf.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(conf.get().general().jbs());
    }

    static Optional<Issue> getIssue(Commit commit, Optional<String> project) throws IOException {
        return project.isEmpty() ? Optional.empty() : getIssue(commit, project.get());
    }

    static Optional<Issue> getIssue(Commit commit, String project) throws IOException {
        var message = CommitMessageParsers.v1.parse(commit.message());
        var issues = message.issues();
        if (issues.isEmpty()) {
            return getIssue(message.title(), project);
        } else if (issues.size() == 1) {
            var issue = issues.get(0);
            return getIssue(issue.id(), project);
        }
        return Optional.empty();
    }

    static Optional<Issue> getIssue(Branch b, Optional<String> project) throws IOException {
        return project.isEmpty() ? Optional.empty() : getIssue(b, project.get());
    }

    static Optional<Issue> getIssue(Branch b, String project) throws IOException {
        return getIssue(b.name(), project);
    }

    static Optional<Issue> getIssue(String s, String project) throws IOException {
        var m = ISSUE_ID_PATTERN.matcher(s);
        if (m.matches()) {
            var id = m.group(2);
            if (project == null) {
                project = m.group(1);
            }
            var issueTracker = IssueTracker.from("jira", URI.create("https://bugs.openjdk.org"));
            return issueTracker.project(project).issue(id);
        }

        return Optional.empty();
    }

    static void await(Process p, Integer... allowedExitCodes) throws IOException {
        var allowed = new HashSet<>(Arrays.asList(allowedExitCodes));
        allowed.add(0);
        try {
            var res = p.waitFor();

            if (!allowed.contains(res)) {
                throw new IOException("Unexpected exit code " + res);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    static boolean spawnEditor(ReadOnlyRepository repo, Path file) throws IOException {
        String editor = null;
        var lines = repo.config("core.editor");
        if (lines.size() == 1) {
            editor = lines.get(0);
        }
        if (editor == null) {
            editor = System.getenv("GIT_EDITOR");
        }
        if (editor == null) {
            editor = System.getenv("EDITOR");
        }
        if (editor == null) {
            editor = System.getenv("VISUAL");
        }
        if (editor == null) {
            editor = "vi";
        }

        // As an editor command may have multiple arguments, we need to add each single one
        // to the ProcessBuilder. Arguments are split by whitespace and can be quoted.
        // e.g. I found core.editor =
        // \"C:\\\\Program Files\\\\Notepad++\\\\notepad++.exe\" -multiInst -notabbar -nosession -noPlugin
        List<String> editorParts = new ArrayList<>();
        Matcher em = Pattern.compile("\\s*([^\"]\\S*|\".+?\")\\s*").matcher(editor);
        while (em.find()) {
            editorParts.add(em.group(1));
        }
        editorParts.add(file.toString());
        var pb = new ProcessBuilder(editorParts);
        pb.inheritIO();
        var p = pb.start();
        try {
            return p.waitFor() == 0;
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    static PullRequest getPullRequest(URI uri, ReadOnlyRepository repo, Forge host, String prId) throws IOException {
        var pr = ForgeUtils.getHostedRepositoryFor(uri, repo, host).pullRequest(prId);
        if (pr == null) {
            exit("error: could not fetch PR information");
        }

        return pr;
    }

    static void show(String ref, Hash hash, boolean useTool) throws IOException {
        show(ref, hash, null, useTool);
    }

    static void show(String ref, Hash hash, Path dir, boolean useTool) throws IOException {
        var command = useTool ? "difftool" : "diff";
        var pb = new ProcessBuilder("git", command, "--binary",
                                                   "--patch",
                                                   "--find-renames=50%",
                                                   "--find-copies=50%",
                                                   "--find-copies-harder",
                                                   "--abbrev",
                                                   ref + "..." + hash.hex());
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.inheritIO();

        // git will return 141 (128 + 13) when it receive SIGPIPE (signal 13) from
        // e.g. less when a user exits less when looking at a large diff. Therefore
        // must allow 141 as a valid exit code.
        await(pb.start(), 141);
    }

    static Path diff(String ref, Hash hash) throws IOException {
        return diff(ref, hash, null);
    }

    static Path diff(String ref, Hash hash, Path dir) throws IOException {
        var patch = Files.createTempFile(hash.hex(), ".patch");
        var pb = new ProcessBuilder("git", "diff", "--binary",
                                                   "--patch",
                                                   "--find-renames=50%",
                                                   "--find-copies=50%",
                                                   "--find-copies-harder",
                                                   "--abbrev",
                                                   ref + "..." + hash.hex());
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.redirectOutput(patch.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        await(pb.start());
        return patch;
    }

    static void apply(Path patch) throws IOException {
        var pb = new ProcessBuilder("git", "apply", patch.toString());
        pb.inheritIO();
        await(pb.start());
    }

    static String removeTrailing(String s, String trail) {
        return s.endsWith(trail) ?
            s.substring(0, s.length() - trail.length()) :
            s;
    }

    static Repository getRepo() throws IOException {
        var cwd = Path.of("").toAbsolutePath();
        return Repository.get(cwd).orElseThrow(() -> new IOException("no git repository found at " + cwd.toString()));
    }

    static Arguments parse(ArgumentParser parser, String[] args) {
        var arguments = parser.parse(args);
        if (arguments.contains("version")) {
            System.out.println("git-pr version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }
        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }
        return arguments;
    }

    static String getRemote(ReadOnlyRepository repo, Arguments arguments) throws IOException {
        return ForgeUtils.getRemote(repo, "pr", arguments);
    }

    static URI getURI(ReadOnlyRepository repo, Arguments arguments) throws IOException {
        return ForgeUtils.getURI(repo, "pr", arguments);
    }

    static Forge getForge(URI uri, ReadOnlyRepository repo, Arguments arguments) throws IOException {
        return ForgeUtils.getForge(uri, repo, "pr", arguments);
    }

    public static Optional<Comment> awaitReplyTo(PullRequest pr, Comment command) throws InterruptedException {
        for (var i = 0; i < 90; i++) {
            for (var comment : pr.comments()) {
                if (comment.body().startsWith("<!-- Jmerge command reply message (" + command.id()  + ") -->\n")) {
                    return Optional.of(comment);
                }
            }
            Thread.sleep(2000);
        }

        return Optional.empty();
    }

    public static void showReply(Optional<Comment> reply) {
        if (reply.isEmpty()) {
            System.err.println("error: timed out while waiting for reply");
            System.exit(1);
        }

        var lines = Arrays.asList(reply.get().body().split("\n"));
        for (var line : lines) {
            if (line.startsWith("<!--") && line.endsWith("-->")) {
                continue;
            }

            System.out.println(line);
        }
    }
}
