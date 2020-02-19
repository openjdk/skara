/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.args.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class GitPr {
    private static final Pattern ISSUE_ID_PATTERN = Pattern.compile("([A-Za-z][A-Za-z0-9]+)?-([0-9]+)");
    private static final Pattern ISSUE_MARKDOWN_PATTERN =
        Pattern.compile("^\\[([A-Z]+-[0-9]+)\\]\\(https:\\/\\/bugs.openjdk.java.net\\/browse\\/[A-Z]+-[0-9]+\\): .*$");

    private static void exit(String fmt, Object...args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    private static String gitConfig(String key) {
        try {
            var pb = new ProcessBuilder("git", "config", key);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();

            var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var res = p.waitFor();
            if (res != 0) {
                return null;
            }

            return output == null ? null : output.replace("\n", "");
        } catch (InterruptedException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String getOption(String name, Arguments arguments) {
        return getOption(name, null, arguments);
    }

    private static String getOption(String name, String subsection, Arguments arguments) {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        if (subsection != null && !subsection.isEmpty()) {
            var subsectionSpecific = gitConfig("pr." + subsection + "." + name);
            if (subsectionSpecific != null) {
                return subsectionSpecific;
            }
        }

        return gitConfig("fork." + name);
    }

    private static boolean getSwitch(String name, Arguments arguments) {
        return getSwitch(name, null, arguments);
    }

    private static boolean getSwitch(String name, String subsection, Arguments arguments) {
        if (arguments.contains(name)) {
            return true;
        }

        if (subsection != null && !subsection.isEmpty()) {
            var subsectionSpecific = gitConfig("pr." + subsection + "." + name);
            if (subsectionSpecific != null) {
                return subsectionSpecific.toLowerCase().equals("true");
            }
        }

        var sectionSpecific = gitConfig("fork." + name);
        return sectionSpecific != null && sectionSpecific.toLowerCase().equals("true");
    }

    private static String rightPad(String s, int length) {
        return String.format("%-" + length + "s", s);
    }

    private static void appendPaddedHTMLComment(Path file, String line) throws IOException {
        var end = " -->";
        var pad = 79 - end.length();
        var newLine = "\n";
        Files.writeString(file, rightPad("<!-- " + line, pad) + end + newLine, StandardOpenOption.APPEND);
    }

    private static String format(Issue issue) {
        var parts = issue.id().split("-");
        var id = parts.length == 2 ? parts[1] : issue.id();
        return id + ": " + issue.title();
    }


    private static String pullRequestIdArgument(Arguments arguments, ReadOnlyRepository repo) throws IOException {
        if (arguments.at(1).isPresent()) {
            return arguments.at(1).asString();
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

    private static String statusForPullRequest(PullRequest pr) {
        var labels = pr.labels();
        if (pr.isDraft()) {
            return "DRAFT";
        } else if (labels.contains("integrated")) {
            return "INTEGRATED";
        } else if (labels.contains("ready")) {
            return "READY";
        } else if (labels.contains("rfr")) {
            return "RFR";
        } else if (labels.contains("outdated")) {
            return "OUTDATED";
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

    private static String statusForCheck(Check check) {
        var checkStatus = check.status();
        if (checkStatus == CheckStatus.IN_PROGRESS) {
            return "RUNNING";
        } else if (checkStatus == CheckStatus.SUCCESS) {
            return "OK";
        } else if (checkStatus == CheckStatus.FAILURE) {
            return "FAILED";
        } else if (checkStatus == CheckStatus.CANCELLED) {
            return "CANCELLED";
        }

        return "UNKNOWN";
    }

    private static List<String> issuesFromPullRequest(PullRequest pr) {
        var issueTitleIndex = -1;
        var lines = pr.body().split("\n");
        for (var i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("## Issue")) {
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

    private static String jbsProjectFromJcheckConf(Repository repo, String targetBranch) throws IOException {
        var conf = JCheckConfiguration.from(repo, repo.resolve(targetBranch).orElseThrow(() ->
            new IOException("Could not resolve '" + targetBranch + "' branch")
        ));

        return conf.general().jbs();
    }

    private static Optional<Issue> getIssue(Commit commit, String project) throws IOException {
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

    private static Optional<Issue> getIssue(Branch b, String project) throws IOException {
        return getIssue(b.name(), project);
    }

    private static Optional<Issue> getIssue(String s, String project) throws IOException {
        var m = ISSUE_ID_PATTERN.matcher(s);
        if (m.matches()) {
            var id = m.group(2);
            if (project == null) {
                project = m.group(1);
            }
            var issueTracker = IssueTracker.from("jira", URI.create("https://bugs.openjdk.java.net"));
            return issueTracker.project(project).issue(id);
        }

        return Optional.empty();
    }

    private static void await(Process p, Integer... allowedExitCodes) throws IOException {
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

    private static boolean spawnEditor(ReadOnlyRepository repo, Path file) throws IOException {
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

    private static String projectName(URI uri) {
        var name = uri.getPath().toString().substring(1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        return name;
    }

    private static HostedRepository getHostedRepositoryFor(URI uri, ReadOnlyRepository repo, Forge host) throws IOException {
        HostedRepository targetRepo = null;

        try {
            var upstream = Remote.toWebURI(repo.pullPath("upstream"));
            targetRepo = host.repository(projectName(upstream)).orElse(null);
        } catch (IOException e) {
            // do nothing
        }

        if (targetRepo == null) {
            var remoteRepo = host.repository(projectName(uri)).orElseThrow(() ->
                    new IOException("Could not find repository at: " + uri.toString())
            );
            var parentRepo = remoteRepo.parent();
            targetRepo = parentRepo.isPresent() ? parentRepo.get() : remoteRepo;
        }

        return targetRepo;
    }

    private static PullRequest getPullRequest(URI uri, ReadOnlyRepository repo, Forge host, String prId) throws IOException {
        var pr = getHostedRepositoryFor(uri, repo, host).pullRequest(prId);
        if (pr == null) {
            exit("error: could not fetch PR information");
        }

        return pr;
    }

    private static void show(String ref, Hash hash) throws IOException {
        show(ref, hash, null);
    }
    private static void show(String ref, Hash hash, Path dir) throws IOException {
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
        pb.inheritIO();

        // git will return 141 (128 + 13) when it receive SIGPIPE (signal 13) from
        // e.g. less when a user exits less when looking at a large diff. Therefore
        // must allow 141 as a valid exit code.
        await(pb.start(), 141);
    }

    private static void gimport() throws IOException {
        var pb = new ProcessBuilder("hg", "gimport");
        pb.inheritIO();
        await(pb.start());
    }

    private static void hgImport(Path patch) throws IOException {
        var pb = new ProcessBuilder("hg", "import", "--no-commit", patch.toAbsolutePath().toString());
        pb.inheritIO();
        await(pb.start());
    }

    private static List<String> hgTags() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("hg", "tags", "--quiet");
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var p = pb.start();
        var bytes = p.getInputStream().readAllBytes();
        var exited = p.waitFor(1, TimeUnit.MINUTES);
        var exitValue = p.exitValue();
        if (!exited || exitValue != 0) {
            throw new IOException("'hg tags' exited with value: " + exitValue);
        }

        return Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n"));
    }

    private static String hgResolve(String ref) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("hg", "log", "-r", ref, "--template", "{node}");
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var p = pb.start();
        var bytes = p.getInputStream().readAllBytes();
        var exited = p.waitFor(1, TimeUnit.MINUTES);
        var exitValue = p.exitValue();
        if (!exited || exitValue != 0) {
            throw new IOException("'hg log' exited with value: " + exitValue);
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Path diff(String ref, Hash hash) throws IOException {
        return diff(ref, hash, null);
    }

    private static Path diff(String ref, Hash hash, Path dir) throws IOException {
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

    private static void apply(Path patch) throws IOException {
        var pb = new ProcessBuilder("git", "apply", "--no-commit", patch.toString());
        pb.inheritIO();
        await(pb.start());
    }

    private static int longest(List<String> strings) {
        return strings.stream().mapToInt(String::length).max().orElse(0);
    }

    private static String removeTrailing(String s, String trail) {
        return s.endsWith(trail) ?
            s.substring(0, s.length() - trail.length()) :
            s;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Username on host")
                  .optional(),
            Option.shortcut("r")
                  .fullname("remote")
                  .describe("NAME")
                  .helptext("Name of remote, defaults to 'origin'")
                  .optional(),
            Option.shortcut("b")
                  .fullname("branch")
                  .describe("NAME")
                  .helptext("Name of target branch, defaults to 'master'")
                  .optional(),
            Option.shortcut("")
                  .fullname("authors")
                  .describe("LIST")
                  .helptext("Comma separated list of authors")
                  .optional(),
            Option.shortcut("")
                  .fullname("assignees")
                  .describe("LIST")
                  .helptext("Comma separated list of assignees")
                  .optional(),
            Option.shortcut("")
                  .fullname("labels")
                  .describe("LIST")
                  .helptext("Comma separated list of labels")
                  .optional(),
            Option.shortcut("")
                  .fullname("issues")
                  .describe("LIST")
                  .helptext("Comma separated list of issues")
                  .optional(),
            Option.shortcut("")
                  .fullname("columns")
                  .describe("id,title,author,assignees,labels")
                  .helptext("Comma separated list of columns to show")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-decoration")
                  .helptext("Hide any decorations when listing PRs")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-draft")
                  .helptext("Hide all pull requests in draft state")
                  .optional(),
            Switch.shortcut("")
                  .fullname("draft")
                  .helptext("Create a pull request in draft state")
                  .optional(),
            Switch.shortcut("")
                  .fullname("ignore-workspace")
                  .helptext("Ignore local changes in worktree and staging area when creating pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("ignore-local-commits")
                  .helptext("Ignore local commits not pushed when creating pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("publish")
                  .helptext("Publish the local branch before creating the pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("atomic")
                  .helptext("Integrate the pull request atomically")
                  .optional(),
            Switch.shortcut("")
                  .fullname("jcheck")
                  .helptext("Run jcheck before creating the pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-token")
                  .helptext("Do not use a personal access token (PAT). Only works for read-only operations.")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-checks")
                  .helptext("Do not show check status as part of the 'git pr status' output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("mercurial")
                  .helptext("Force use of Mercurial (hg)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("debug")
                  .helptext("Turn on debugging output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional());

        var actions = List.of("list", "fetch", "show", "checkout", "apply", "integrate",
                              "approve", "create", "close", "set", "test", "status");
        var inputs = List.of(
            Input.position(0)
                 .describe(String.join("|", actions))
                 .singular()
                 .optional(),
            Input.position(1)
                 .describe("ID")
                 .singular()
                 .optional()
        );

        var parser = new ArgumentParser("git-pr", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-pr version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        HttpProxy.setup();

        var isMercurial = getSwitch("mercurial", arguments);
        var cwd = Path.of("").toAbsolutePath();
        var repo = Repository.get(cwd).orElseThrow(() -> new IOException("no git repository found at " + cwd.toString()));
        var remote = getOption("remote", arguments);
        if (remote == null) {
            remote = isMercurial ? "default" : "origin";
        }
        var remotePullPath = repo.pullPath(remote);
        var username = getOption("username", arguments);
        var token = isMercurial ? System.getenv("HG_TOKEN") : System.getenv("GIT_TOKEN");
        var uri = Remote.toWebURI(remotePullPath);
        var shouldUseToken = !getSwitch("no-token", arguments);
        var credentials = !shouldUseToken ?
            null :
            GitCredentials.fill(uri.getHost(), uri.getPath(), username, token, uri.getScheme());
        var forgeURI = URI.create(uri.getScheme() + "://" + uri.getHost());
        var forge = credentials == null ?
            Forge.from(forgeURI) :
            Forge.from(forgeURI, new Credential(credentials.username(), credentials.password()));
        if (forge.isEmpty()) {
            if (!shouldUseToken) {
                if (arguments.contains("verbose")) {
                    System.err.println("");
                }
                System.err.println("warning: using git-pr with --no-token may result in rate limiting from " + forgeURI);
                if (!arguments.contains("verbose")) {
                    System.err.println("         Re-run git-pr with --verbose to see if you are being rate limited");
                    System.err.println("");
                }
            }
            exit("error: failed to connect to host: " + forgeURI);
        }
        var host = forge.get();

        var action = arguments.at(0).isPresent() ? arguments.at(0).asString() : null;
        if (action == null) {
            var lines = repo.config("pr.default");
            if (lines.size() == 1) {
                action = lines.get(0);
            }
        }

        if (action == null) {
            System.err.println("error: you must supply a valid action:");
            for (var a : actions) {
                System.err.println("       - " + a);
            }
            System.err.println("You can also configure a default action by running 'git configure --global pr.default <action>'");
            System.exit(1);
        }

        if (!shouldUseToken &&
            !List.of("list", "fetch", "show", "checkout", "apply").contains(action)) {
            System.err.println("error: --no-token can only be used with read-only operations");
            System.exit(1);
        }

        if (action.equals("create")) {
            if (isMercurial) {
                var currentBookmark = repo.currentBookmark();
                if (!currentBookmark.isPresent()) {
                    System.err.println("error: no bookmark is active, you must be on an active bookmark");
                    System.err.println("");
                    System.err.println("To create a bookmark and activate it, run:");
                    System.err.println("");
                    System.err.println("    hg bookmark NAME-FOR-YOUR-BOOKMARK");
                    System.err.println("");
                    System.exit(1);
                }

                var bookmark = currentBookmark.get();
                if (bookmark.equals(new Bookmark("master"))) {
                    System.err.println("error: you should not create pull requests from the 'master' bookmark");
                    System.err.println("To create a bookmark and activate it, run:");
                    System.err.println("");
                    System.err.println("    hg bookmark NAME-FOR-YOUR-BOOKMARK");
                    System.err.println("");
                    System.exit(1);
                }

                var tags = hgTags();
                var upstreams = tags.stream()
                                    .filter(t -> t.endsWith(bookmark.name()))
                                    .collect(Collectors.toList());
                if (upstreams.isEmpty()) {
                    System.err.println("error: there is no remote branch for the local bookmark '" + bookmark.name() + "'");
                    System.err.println("");
                    System.err.println("To create a remote branch and push the commits for your local branch, run:");
                    System.err.println("");
                    System.err.println("    hg push --bookmark " + bookmark.name());
                    System.err.println("");
                    System.exit(1);
                }

                var tagsAndHashes = new HashMap<String, String>();
                for (var tag : tags) {
                    tagsAndHashes.put(tag, hgResolve(tag));
                }
                var bookmarkHash = hgResolve(bookmark.name());
                if (!tagsAndHashes.containsValue(bookmarkHash)) {
                    System.err.println("error: there are local commits on bookmark '" + bookmark.name() + "' not present in a remote repository");
                    System.err.println("");

                    if (upstreams.size() == 1) {
                        System.err.println("To push the local commits to the remote repository, run:");
                        System.err.println("");
                        System.err.println("    hg push --bookmark " + bookmark.name() + " " + upstreams.get(0));
                        System.err.println("");
                    } else {
                        System.err.println("The following paths contains the " + bookmark.name() + " bookmark:");
                        System.err.println("");
                        for (var upstream : upstreams) {
                            System.err.println("- " + upstream.replace("/" + bookmark.name(), ""));
                        }
                        System.err.println("");
                        System.err.println("To push the local commits to a remote repository, run:");
                        System.err.println("");
                        System.err.println("    hg push --bookmark " + bookmark.name() + " <PATH>");
                        System.err.println("");
                    }
                    System.exit(1);
                }

                var targetBranch = arguments.get("branch").orString("master");
                var targetHash = hgResolve(targetBranch);
                var commits = repo.commits(targetHash + ".." + bookmarkHash + "-" + targetHash).asList();
                if (commits.isEmpty()) {
                    System.err.println("error: no difference between bookmarks " + targetBranch + " and " + bookmark.name());
                    System.err.println("       Cannot create an empty pull request, have you committed?");
                    System.exit(1);
                }

                var diff = repo.diff(repo.head());
                if (!diff.patches().isEmpty()) {
                    System.err.println("error: there are uncommitted changes in your working tree:");
                    System.err.println("");
                    for (var patch : diff.patches()) {
                        var path = patch.target().path().isPresent() ?
                            patch.target().path().get() : patch.source().path().get();
                        System.err.println("    " + patch.status().toString() + " " + path.toString());
                    }
                    System.err.println("");
                    System.err.println("If these changes are meant to be part of the pull request, run:");
                    System.err.println("");
                    System.err.println("    hg commit --amend");
                    System.err.println("    hg git-cleanup");
                    System.err.println("    hg push --bookmark " + bookmark.name() + " <PATH>");
                    System.err.println("    hg gimport");
                    System.err.println("");
                    System.err.println("If these changes are *not* meant to be part of the pull request, run:");
                    System.err.println("");
                    System.err.println("    hg shelve");
                    System.err.println("");
                    System.err.println("(You can later restore the changes by running: hg unshelve)");
                    System.exit(1);
                }

                var remoteRepo = host.repository(projectName(uri)).orElseThrow(() ->
                        new IOException("Could not find repository at " + uri.toString())
                );
                if (token == null) {
                    GitCredentials.approve(credentials);
                }
                var parentRepo = remoteRepo.parent().orElseThrow(() ->
                        new IOException("error: remote repository " + remotePullPath + " is not a fork of any repository"));

                var file = Files.createTempFile("PULL_REQUEST_", ".txt");
                if (commits.size() == 1) {
                    var commit = commits.get(0);
                    var message = CommitMessageParsers.v1.parse(commit.message());
                    Files.writeString(file, message.title() + "\n");
                    if (!message.summaries().isEmpty()) {
                        Files.write(file, message.summaries(), StandardOpenOption.APPEND);
                    }
                    if (!message.additional().isEmpty()) {
                        Files.write(file, message.additional(), StandardOpenOption.APPEND);
                    }
                } else {
                    Files.write(file, List.of(""));
                }
                Files.write(file, List.of(
                    "# Please enter the pull request message for your changes. Lines starting",
                    "# with '#' will be ignored, and an empty message aborts the pull request.",
                    "# The first line will be considered the subject, use a blank line to separate",
                    "# the subject from the body.",
                    "#",
                    "# Commits to be included from branch '" + bookmark.name() + "'"
                    ),
                    StandardOpenOption.APPEND
                );
                for (var commit : commits) {
                    var desc = commit.hash().abbreviate() + ": " + commit.message().get(0);
                    Files.writeString(file, "# - " + desc + "\n", StandardOpenOption.APPEND);
                }
                Files.writeString(file, "#\n", StandardOpenOption.APPEND);
                Files.writeString(file, "# Target repository: " + remotePullPath + "\n", StandardOpenOption.APPEND);
                Files.writeString(file, "# Target branch: " + targetBranch + "\n", StandardOpenOption.APPEND);
                var success = spawnEditor(repo, file);
                if (!success) {
                    System.err.println("error: editor exited with non-zero status code, aborting");
                    System.exit(1);
                }
                var lines = Files.readAllLines(file)
                                 .stream()
                                 .filter(l -> !l.startsWith("#"))
                                 .collect(Collectors.toList());
                var isEmpty = lines.stream().allMatch(String::isEmpty);
                if (isEmpty) {
                    System.err.println("error: no message present, aborting");
                    System.exit(1);
                }

                var title = lines.get(0);
                List<String> body = null;
                if (lines.size() > 1) {
                    body = lines.subList(1, lines.size())
                                .stream()
                                .dropWhile(String::isEmpty)
                                .collect(Collectors.toList());
                } else {
                    body = Collections.emptyList();
                }

                var pr = remoteRepo.createPullRequest(parentRepo, targetBranch, bookmark.name(), title, body);
                if (arguments.contains("assignees")) {
                    var usernames = Arrays.asList(arguments.get("assignees").asString().split(","));
                    var assignees = usernames.stream()
                                             .map(u -> host.user(u))
                                             .filter(Optional::isPresent)
                                             .map(Optional::get)
                                             .collect(Collectors.toList());
                    pr.setAssignees(assignees);
                }
                System.out.println(pr.webUrl().toString());
                Files.deleteIfExists(file);

                System.exit(0);
            }
            var currentBranch = repo.currentBranch().orElseGet(() -> {
                    System.err.println("error: the repository is in a detached HEAD state");
                    System.exit(1);
                    return null;
            });
            if (currentBranch.equals(repo.defaultBranch())) {
                System.err.println("error: you should not create pull requests from the 'master' branch");
                System.err.println("");
                System.err.println("To create a local branch for your changes and restore the 'master' branch, run:");
                System.err.println("");
                System.err.println("    git checkout -b NAME-FOR-YOUR-LOCAL-BRANCH");
                System.err.println("    git branch --force master origin/master");
                System.err.println("");
                System.exit(1);
            }

            var ignoreWorkspace = getSwitch("ignore-workspace", "create", arguments);
            if (!ignoreWorkspace) {
                var diff = repo.diff(repo.head());
                if (!diff.patches().isEmpty()) {
                    System.err.println("error: there are uncommitted changes in your working tree:");
                    System.err.println("");
                    for (var patch : diff.patches()) {
                        var path = patch.target().path().isPresent() ?
                            patch.target().path().get() : patch.source().path().get();
                        System.err.println("    " + patch.status().toString() + " " + path.toString());
                    }
                    System.err.println("");
                    System.err.println("If these changes are meant to be part of the pull request, run:");
                    System.err.println("");
                    System.err.println("    git commit -am 'Forgot to add some changes'");
                    System.err.println("");
                    System.err.println("If these changes are *not* meant to be part of the pull request, run:");
                    System.err.println("");
                    System.err.println("    git stash");
                    System.err.println("");
                    System.err.println("(You can later restore the changes by running: git stash pop)");
                    System.exit(1);
                }
            }

            var upstream = repo.upstreamFor(currentBranch);
            if (upstream.isEmpty()) {
                var shouldPublish = getSwitch("publish", "create", arguments);
                if (shouldPublish) {
                    GitPublish.main(new String[] { "--quiet", remote });
                    upstream = repo.upstreamFor(currentBranch);
                } else {
                    System.err.println("error: there is no remote branch for the local branch '" + currentBranch.name() + "'");
                    System.err.println("");
                    System.err.println("A remote branch must be present at " + remotePullPath + " to create a pull request");
                    System.err.println("To create a remote branch and push the commits for your local branch, run:");
                    System.err.println("");
                    System.err.println("    git publish");
                    System.err.println("");
                    System.err.println("If you created the remote branch from another client, you must update this repository.");
                    System.err.println("To update remote information for this repository, run:");
                    System.err.println("");
                    System.err.println("    git fetch " + remote);
                    System.err.println("    git branch --set-upstream " + currentBranch + " " + remote + "/" + currentBranch);
                    System.err.println("");
                    System.exit(1);
                }
            }

            var upstreamRefName = upstream.get().substring(remote.length() + 1);
            repo.fetch(uri, upstreamRefName);

            var shouldIgnoreLocalCommits = getSwitch("ignore-local-commits", "create", arguments);
            if (!shouldIgnoreLocalCommits) {
                var branchCommits = repo.commits(upstream.get() + ".." + currentBranch.name()).asList();
                if (!branchCommits.isEmpty()) {
                    System.err.println("error: there are local commits on branch '" + currentBranch.name() + "' not present in the remote repository " + remotePullPath);
                    System.err.println("");
                    System.err.println("All commits must be present in the remote repository to be part of the pull request");
                    System.err.println("The following commits are not present in the remote repository:");
                    System.err.println("");
                    for (var commit : branchCommits) {
                        System.err.println("- " + commit.hash().abbreviate() + ": " + commit.message().get(0));
                    }
                    System.err.println("");
                    System.err.println("To push the above local commits to the remote repository, run:");
                    System.err.println("");
                    System.err.println("    git push " + remote + " " + currentBranch.name());
                    System.err.println("");
                    System.exit(1);
                }
            }

            var remoteRepo = host.repository(projectName(uri)).orElseThrow(() ->
                    new IOException("Could not find repository at " + uri.toString())
            );
            if (token == null) {
                GitCredentials.approve(credentials);
            }
            var parentRepo = remoteRepo.parent().orElseThrow(() ->
                    new IOException("error: remote repository " + remotePullPath + " is not a fork of any repository")
            );

            var targetBranch = getOption("branch", "create", arguments);
            if (targetBranch == null) {
                var upstreamBranchNames = repo.remoteBranches(parentRepo.webUrl().toString())
                                              .stream()
                                              .map(r -> r.name())
                                              .collect(Collectors.toSet());
                var remoteBranches = repo.branches(remote);
                var candidates = new ArrayList<Branch>();
                for (var b : remoteBranches) {
                    var withoutRemotePrefix = b.name().substring(remote.length() + 1);
                    if (upstreamBranchNames.contains(withoutRemotePrefix)) {
                        candidates.add(b);
                    }
                }

                var localBranches = repo.branches();
                Branch closest = null;
                var shortestDistance = Integer.MAX_VALUE;
                for (var b : candidates) {
                    var from = b.name();
                    for (var localBranch : localBranches) {
                        var trackingBranch = repo.upstreamFor(localBranch);
                        if (trackingBranch.isPresent() &&
                            trackingBranch.get().equals(b.name())) {
                            from = localBranch.name();
                        }
                    }
                    var distance = repo.commitMetadata(from + "..." + currentBranch.name()).size();
                    if (distance < shortestDistance) {
                        closest = b;
                        shortestDistance = distance;
                    }
                }

                if (closest != null) {
                    targetBranch = closest.name().substring(remote.length() + 1);
                } else {
                    System.err.println("error: cannot automatically infer target branch");
                    System.err.println("       use --branch to specify target branch");
                    System.exit(1);
                }
            }
            var commits = repo.commits(targetBranch + ".." + upstream.get()).asList();
            if (commits.isEmpty()) {
                System.err.println("error: no difference between branches " + targetBranch + " and " + currentBranch.name());
                System.err.println("       Cannot create an empty pull request, have you committed?");
                System.exit(1);
            }

            var shouldRunJCheck = getSwitch("jcheck", "create", arguments);
            if (shouldRunJCheck) {
                var jcheckArgs = new String[]{ "--pull-request", "--rev", targetBranch + ".." + upstream.get() };
                var err = GitJCheck.run(jcheckArgs);
                if (err != 0) {
                    System.exit(err);
                }
            }

            var project = jbsProjectFromJcheckConf(repo, targetBranch);
            var issue = getIssue(currentBranch, project);
            var file = Files.createTempFile("PULL_REQUEST_", ".md");
            if (issue.isPresent()) {
                Files.writeString(file, format(issue.get()) + "\n\n");
            } else if (commits.size() == 1) {
                var commit = commits.get(0);
                issue = getIssue(commit, project);
                if (issue.isPresent()) {
                    Files.writeString(file, format(issue.get()) + "\n\n");
                } else {
                    var message = CommitMessageParsers.v1.parse(commit.message());
                    Files.writeString(file, message.title() + "\n");
                    if (!message.summaries().isEmpty()) {
                        Files.write(file, message.summaries(), StandardOpenOption.APPEND);
                    }
                    if (!message.additional().isEmpty()) {
                        Files.write(file, message.additional(), StandardOpenOption.APPEND);
                    }
                }
            } else {
                Files.write(file, List.of(""));
            }

            appendPaddedHTMLComment(file, "Please enter the pull request message for your changes.");
            appendPaddedHTMLComment(file, "The first line will be considered the subject, use a blank line to");
            appendPaddedHTMLComment(file, "separate the subject from the body. These HTML comment lines will");
            appendPaddedHTMLComment(file, "be removed automatically. An empty message aborts the pull request.");
            appendPaddedHTMLComment(file, "");
            appendPaddedHTMLComment(file, "Commits to be included from branch '" + currentBranch.name() + "':");
            for (var commit : commits) {
                var desc = commit.hash().abbreviate() + ": " + commit.message().get(0);
                appendPaddedHTMLComment(file, "- " + desc);
                if (!commit.isMerge()) {
                    var diff = commit.parentDiffs().get(0);
                    for (var patch : diff.patches()) {
                        var status = patch.status();
                        if (status.isModified()) {
                            appendPaddedHTMLComment(file, "  M  " + patch.target().path().get().toString());
                        } else if (status.isAdded()) {
                            appendPaddedHTMLComment(file, "  A  " + patch.target().path().get().toString());
                        } else if (status.isDeleted()) {
                            appendPaddedHTMLComment(file, "  D  " + patch.source().path().get().toString());
                        } else if (status.isRenamed()) {
                            appendPaddedHTMLComment(file, "  R  " + patch.target().path().get().toString());
                            appendPaddedHTMLComment(file, "      (" + patch.source().path().get().toString() + ")");
                        } else if (status.isCopied()) {
                            appendPaddedHTMLComment(file, "  C  " + patch.target().path().get().toString());
                            appendPaddedHTMLComment(file, "      (" + patch.source().path().get().toString() + ")");
                        }
                    }
                }
            }
            appendPaddedHTMLComment(file, "");
            if (issue.isPresent()) {
                appendPaddedHTMLComment(file, "Issue:      " + issue.get().webUrl());
            }
            appendPaddedHTMLComment(file, "Repository: " + parentRepo.webUrl());
            appendPaddedHTMLComment(file, "Branch:     " + targetBranch);

            var success = spawnEditor(repo, file);
            if (!success) {
                System.err.println("error: editor exited with non-zero status code, aborting");
                System.exit(1);
            }
            var lines = Files.readAllLines(file)
                             .stream()
                             .filter(l -> !(l.startsWith("<!--") && l.endsWith("-->")))
                             .collect(Collectors.toList());
            var isEmpty = lines.stream().allMatch(String::isEmpty);
            if (isEmpty) {
                System.err.println("error: no message present, aborting");
                System.exit(1);
            }

            var title = lines.get(0);
            List<String> body = null;
            if (lines.size() > 1) {
                body = lines.subList(1, lines.size())
                            .stream()
                            .dropWhile(String::isEmpty)
                            .collect(Collectors.toList());
            } else {
                body = Collections.emptyList();
            }

            var isDraft = getSwitch("draft", "create", arguments);
            var pr = remoteRepo.createPullRequest(parentRepo, targetBranch, currentBranch.name(), title, body, isDraft);
            var assigneesOption = getOption("assignees", "create", arguments);
            if (assigneesOption != null) {
                var usernames = Arrays.asList(assigneesOption.split(","));
                var assignees = usernames.stream()
                                         .map(u -> host.user(u))
                                         .filter(Optional::isPresent)
                                         .map(Optional::get)
                                         .collect(Collectors.toList());
                pr.setAssignees(assignees);
            }
            System.out.println(pr.webUrl().toString());
            Files.deleteIfExists(file);

            repo.config("pr." + currentBranch.name(), "id", pr.id().toString());
        } else if (action.equals("integrate")) {
            var id = pullRequestIdArgument(arguments, repo);
            var pr = getPullRequest(uri, repo, host, id);
            var isAtomic = getSwitch("atomic", "integrate", arguments);

            var message = "/integrate";
            if (isAtomic) {
                var targetHash = repo.resolve(pr.targetRef());
                if (!targetHash.isPresent()) {
                    exit("error: cannot resolve target branch " + pr.targetRef());
                }
                var sourceHash = repo.fetch(pr.repository().webUrl(), pr.fetchRef());
                var mergeBase = repo.mergeBase(sourceHash, targetHash.get());
                message += " " + mergeBase.hex();
            }

            var integrateComment = pr.addComment(message);

            var seenIntegrateComment = false;
            var expected = "<!-- Jmerge command reply message (" + integrateComment.id() + ") -->";
            for (var i = 0; i < 90; i++) {
                var comments = pr.comments();
                for (var comment : comments) {
                    if (!seenIntegrateComment) {
                        if (comment.id().equals(integrateComment.id())) {
                            seenIntegrateComment = true;
                        }
                        continue;
                    }
                    var lines = comment.body().split("\n");
                    if (lines.length > 0 && lines[0].equals(expected)) {
                        for (var line : lines) {
                            if (line.startsWith("Pushed as commit")) {
                                var output = removeTrailing(line, ".");
                                System.out.println(output);
                                System.exit(0);
                            }
                        }
                    }
                }

                Thread.sleep(2000);
            }

            System.err.println("error: timed out waiting for response to /integrate command");
            System.exit(1);
        } else if (action.equals("test")) {
            var id = pullRequestIdArgument(arguments, repo);
            var pr = getPullRequest(uri, repo, host, id);
            var head = pr.headHash();
            var testComment = pr.addComment("/test");

            var seenTestComment = false;
            for (var i = 0; i < 90; i++) {
                var comments = pr.comments();
                for (var comment : comments) {
                    if (!seenTestComment) {
                        if (comment.id().equals(testComment.id())) {
                            seenTestComment = true;
                        }
                        continue;
                    }
                    var lines = comment.body().split("\n");
                    var n = lines.length;
                    if (n > 0) {
                        if (n == 4 &&
                            lines[0].equals("<!-- TEST STARTED -->") &&
                            lines[1].startsWith("<!-- github.com-") &&
                            lines[2].equals("<!-- " + head.hex() + " -->")) {
                            var output = removeTrailing(lines[3], ".");
                            System.out.println(output);
                            System.exit(0);
                        } else if (n == 2 &&
                                   lines[0].equals("<!-- TEST ERROR -->")) {
                            var output = removeTrailing(lines[1], ".");
                            System.out.println(output);
                            System.exit(1);
                        } else if (n == 4 &&
                                   lines[0].equals("<!-- TEST PENDING -->") &&
                                   lines[1].equals("<!--- " + head.hex() + " -->")) {
                            var output = removeTrailing(lines[3], ".");
                            System.out.println(output);
                            System.exit(0);
                        }
                    }
                }

                Thread.sleep(2000);
            }

        } else if (action.equals("approve")) {
            var id = arguments.at(1).isPresent() ? arguments.at(1).asString() : null;
            if (id == null) {
                exit("error: you must provide a pull request id");
            }
            var pr = getPullRequest(uri, repo, host, id);
            pr.addReview(Review.Verdict.APPROVED, "Looks good!");
        } else if (action.equals("list")) {
            var remoteRepo = getHostedRepositoryFor(uri, repo, host);
            var prs = remoteRepo.pullRequests();
            var ids = new ArrayList<String>();
            var titles = new ArrayList<String>();
            var authors = new ArrayList<String>();
            var assignees = new ArrayList<String>();
            var labels = new ArrayList<String>();
            var issues = new ArrayList<String>();
            var branches = new ArrayList<String>();
            var statuses = new ArrayList<String>();
            var noDraft = getSwitch("no-draft", "list", arguments);

            var authorsOption = getOption("authors", "list", arguments);
            var filterAuthors = authorsOption == null ?
                Set.of() :
                new HashSet<>(Arrays.asList(authorsOption.split(",")));

            var assigneesOption = getOption("assignees", "list", arguments);
            var filterAssignees = assigneesOption == null ?
                Set.of() :
                Arrays.asList(assigneesOption.split(","));

            var labelsOption = getOption("labels", "list", arguments);
            var filterLabels = labelsOption == null ?
                Set.of() :
                Arrays.asList(labelsOption.split(","));

            var issuesOption = getOption("issues", "list", arguments);
            var filterIssues = issuesOption == null ?
                Set.of() :
                Arrays.asList(issuesOption.split(","));

            var columnTitles = List.of("id", "title", "authors", "assignees", "labels", "issues", "branch", "status");
            var columnValues = Map.of(columnTitles.get(0), ids,
                                      columnTitles.get(1), titles,
                                      columnTitles.get(2), authors,
                                      columnTitles.get(3), assignees,
                                      columnTitles.get(4), labels,
                                      columnTitles.get(5), issues,
                                      columnTitles.get(6), branches,
                                      columnTitles.get(7), statuses);
            var columnsOption = getOption("columns", "list", arguments);
            var columns = columnsOption == null ?
                List.of("id", "title", "authors", "status") :
                Arrays.asList(columnsOption.split(","));

            for (var column : columns) {
                if (!columnTitles.contains(column)) {
                    System.err.println("error: unknown column: " + column);
                    System.err.println("       available columns are: " + String.join(",", columnTitles));
                    System.exit(1);
                }
            }

            for (var pr : prs) {
                if (pr.isDraft() && noDraft) {
                    continue;
                }

                var prAuthor = pr.author().userName();
                if (!filterAuthors.isEmpty() && !filterAuthors.contains(prAuthor)) {
                    continue;
                }

                var prAssignees = pr.assignees().stream()
                                    .map(HostUser::userName)
                                    .collect(Collectors.toSet());
                if (!filterAssignees.isEmpty() && !filterAssignees.stream().anyMatch(prAssignees::contains)) {
                    continue;
                }

                var prLabels = new HashSet<>(pr.labels());
                if (!filterLabels.isEmpty() && !filterLabels.stream().anyMatch(prLabels::contains)) {
                    continue;
                }

                var prIssues = new HashSet<>(issuesFromPullRequest(pr));
                if (!filterIssues.isEmpty() && !filterIssues.stream().anyMatch(prIssues::contains)) {
                    continue;
                }


                ids.add(pr.id());
                titles.add(pr.title());
                authors.add(prAuthor);
                assignees.add(String.join(",", prAssignees));
                labels.add(String.join(",", prLabels));
                issues.add(String.join(",", prIssues));

                if (pr.author().userName().equals(credentials.username()) &&
                    pr.sourceRepository().webUrl().equals(uri)) {
                    branches.add(pr.sourceRef());
                } else {
                    branches.add("");
                }

                if (columns.contains("status")) {
                    statuses.add(statusForPullRequest(pr).toLowerCase());
                } else {
                    statuses.add("");
                }
            }


            String fmt = "";
            for (var column : columns.subList(0, columns.size() - 1)) {
                var values = columnValues.get(column);
                var n = Math.max(column.length(), longest(values));
                fmt += "%-" + n + "s    ";
            }
            fmt += "%s\n";

            var noDecoration = getSwitch("no-decoration", "list", arguments);
            if (!ids.isEmpty() && !noDecoration) {
                var upperCase = columns.stream()
                                       .map(String::toUpperCase)
                                       .collect(Collectors.toList());
                System.out.format(fmt, (Object[]) upperCase.toArray(new String[0]));
            }
            for (var i = 0; i < ids.size(); i++) {
                final int n = i;
                var row = columns.stream()
                                 .map(columnValues::get)
                                 .map(values -> values.get(n))
                                 .collect(Collectors.toList());
                System.out.format(fmt, (Object[]) row.toArray(new String[0]));
            }
        } else if (action.equals("fetch") || action.equals("checkout") || action.equals("show") || action.equals("apply")) {
            var prId = arguments.at(1);
            if (!prId.isPresent()) {
                exit("error: missing pull request identifier");
            }

            var remoteRepo = getHostedRepositoryFor(uri, repo, host);
            var pr = remoteRepo.pullRequest(prId.asString());
            var repoUrl = remoteRepo.webUrl();
            var prHeadRef = pr.fetchRef();
            var isHgGit = isMercurial && Repository.exists(repo.root().resolve(".hg").resolve("git"));
            if (isHgGit) {
                var hgGitRepo = Repository.get(repo.root().resolve(".hg").resolve("git")).get();
                var hgGitFetchHead = hgGitRepo.fetch(repoUrl, prHeadRef);

                if (action.equals("show") || action.equals("apply")) {
                    var target = hgGitRepo.fetch(repoUrl, pr.targetRef());
                    var hgGitMergeBase = hgGitRepo.mergeBase(target, hgGitFetchHead);

                    if (action.equals("show")) {
                        show(hgGitMergeBase.hex(), hgGitFetchHead, hgGitRepo.root());
                    } else {
                        var patch = diff(hgGitMergeBase.hex(), hgGitFetchHead, hgGitRepo.root());
                        hgImport(patch);
                        Files.delete(patch);
                    }
                } else if (action.equals("fetch") || action.equals("checkout")) {
                    var hgGitRef = prHeadRef.endsWith("/head") ? prHeadRef.replace("/head", "") : prHeadRef;
                    var hgGitBranches = hgGitRepo.branches();
                    if (hgGitBranches.contains(new Branch(hgGitRef))) {
                        hgGitRepo.delete(new Branch(hgGitRef));
                    }
                    hgGitRepo.branch(hgGitFetchHead, hgGitRef);
                    gimport();
                    var hgFetchHead = repo.resolve(hgGitRef).get();

                    if (action.equals("fetch") && arguments.contains("branch")) {
                        repo.branch(hgFetchHead, arguments.get("branch").asString());
                    } else if (action.equals("checkout")) {
                        repo.checkout(hgFetchHead);
                        if (arguments.contains("branch")) {
                            repo.branch(hgFetchHead, arguments.get("branch").asString());
                        }
                    }
                } else {
                    exit("Unexpected action: " + action);
                }

                return;
            }

            var fetchHead = repo.fetch(repoUrl, pr.fetchRef());
            if (action.equals("fetch")) {
                var branchName = getOption("branch", "fetch", arguments);
                if (branchName != null) {
                    repo.branch(fetchHead, branchName);
                } else {
                    System.out.println(fetchHead.hex());
                }
            } else if (action.equals("checkout")) {
                var branchName = getOption("branch", "checkout", arguments);
                if (branchName != null) {
                    var branch = repo.branch(fetchHead, branchName);
                    repo.checkout(branch, false);
                } else {
                    repo.checkout(fetchHead, false);
                }
            } else if (action.equals("show")) {
                show(pr.targetRef(), fetchHead);
            } else if (action.equals("apply")) {
                var patch = diff(pr.targetRef(), fetchHead);
                apply(patch);
                Files.deleteIfExists(patch);
            }
        } else if (action.equals("close")) {
            var prId = arguments.at(1);
            if (!prId.isPresent()) {
                exit("error: missing pull request identifier");
            }

            var remoteRepo = getHostedRepositoryFor(uri, repo, host);
            var pr = remoteRepo.pullRequest(prId.asString());
            pr.setState(PullRequest.State.CLOSED);
        } else if (action.equals("set")) {
            var prId = arguments.at(1);
            if (!prId.isPresent()) {
                exit("error: missing pull request identifier");
            }

            var remoteRepo = getHostedRepositoryFor(uri, repo, host);
            var pr = remoteRepo.pullRequest(prId.asString());

            var setDraft = getSwitch("draft", "set", arguments);
            if (!pr.isDraft() && setDraft) {
                exit("error: cannot transition non-draft pull request to draft");
            }

            var assigneesOption = getOption("assignees", "set", arguments);
            if (assigneesOption != null) {
                var usernames = Arrays.asList(assigneesOption.split(","));
                var assignees = usernames.stream()
                    .map(u -> host.user(u))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());
                pr.setAssignees(assignees);
            }

            var setNoDraft = getSwitch("no-draft", "set", arguments);
            if (setNoDraft) {
                pr.makeNotDraft();
            }
        } else if (action.equals("status")) {
            String id = pullRequestIdArgument(arguments, repo);
            var pr = getPullRequest(uri, repo, host, id);
            var noDecoration = getSwitch("no-decoration", "status", arguments);
            var decoration = noDecoration ? "" : "Status: ";
            System.out.println(decoration + statusForPullRequest(pr));

            var noChecks = getSwitch("no-checks", "status", arguments);
            if (!noChecks) {
                var checks = pr.checks(pr.headHash());
                var jcheck = Optional.ofNullable(checks.get("jcheck"));
                var submit = Optional.ofNullable(checks.get("submit"));
                var showChecks = jcheck.isPresent() || submit.isPresent();
                if (showChecks) {
                    System.out.println("Checks:");
                    if (jcheck.isPresent()) {
                        System.out.println("- jcheck: " + statusForCheck(jcheck.get()));
                    }
                    if (submit.isPresent()) {
                        System.out.println("- submit: " + statusForCheck(submit.get()));
                    }
                }
            }
        } else {
            exit("error: unexpected action: " + action);
        }
    }
}
