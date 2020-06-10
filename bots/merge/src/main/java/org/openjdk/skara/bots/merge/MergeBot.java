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
package org.openjdk.skara.bots.merge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.jcheck.JCheckConfiguration;

import java.io.IOException;
import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLEncoder;
import java.time.DayOfWeek;
import java.time.Month;
import java.time.temporal.WeekFields;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

class MergeBot implements Bot, WorkItem {
    private final String integrationCommand = "/integrate\n<!-- Valid self-command -->";
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final Path storage;

    private final HostedRepositoryPool pool;
    private final HostedRepository target;
    private final HostedRepository fork;
    private final List<Spec> specs;

    private final Clock clock;

    private final Map<String, Set<Integer>> hourly = new HashMap<>();
    private final Map<String, Set<Integer>> daily = new HashMap<>();
    private final Map<String, Set<Integer>> weekly = new HashMap<>();
    private final Map<String, Set<Month>> monthly = new HashMap<>();
    private final Map<String, Set<Integer>> yearly = new HashMap<>();

    MergeBot(Path storage, HostedRepository target, HostedRepository fork,
             List<Spec> specs) {
        this(storage, target, fork, specs, new Clock() {
            public ZonedDateTime now() {
                return ZonedDateTime.now();
            }
        });
    }

    MergeBot(Path storage, HostedRepository target, HostedRepository fork,
             List<Spec> specs, Clock clock) {
        this.storage = storage;
        this.pool = new HostedRepositoryPool(storage.resolve("seeds"));
        this.target = target;
        this.fork = fork;
        this.specs = specs;
        this.clock = clock;
    }

    final static class Spec {
        final static class Frequency {
            static enum Interval {
                HOURLY,
                DAILY,
                WEEKLY,
                MONTHLY,
                YEARLY;

                boolean isHourly() {
                    return this.equals(HOURLY);
                }

                boolean isDaily() {
                    return this.equals(DAILY);
                }

                boolean isWeekly() {
                    return this.equals(WEEKLY);
                }

                boolean isMonthly() {
                    return this.equals(MONTHLY);
                }

                boolean isYearly() {
                    return this.equals(YEARLY);
                }
            }

            private final Interval interval;
            private final DayOfWeek weekday;
            private final Month month;
            private final int day;
            private final int hour;
            private final int minute;

            private Frequency(Interval interval, DayOfWeek weekday, Month month, int day, int hour, int minute) {
                this.interval = interval;
                this.weekday = weekday;
                this.month = month;
                this.day = day;
                this.hour = hour;
                this.minute = minute;
            }

            static Frequency hourly(int minute) {
                return new Frequency(Interval.HOURLY, null, null, -1, -1, minute);
            }

            static Frequency daily(int hour) {
                return new Frequency(Interval.DAILY, null, null, -1, hour, -1);
            }

            static Frequency weekly(DayOfWeek weekday, int hour) {
                return new Frequency(Interval.WEEKLY, weekday, null, -1, hour, -1);
            }

            static Frequency monthly(int day, int hour) {
                return new Frequency(Interval.MONTHLY, null, null, day, hour, -1);
            }

            static Frequency yearly(Month month, int day, int hour) {
                return new Frequency(Interval.YEARLY, null, month, day, hour, -1);
            }

            boolean isHourly() {
                return interval.isHourly();
            }

            boolean isDaily() {
                return interval.isDaily();
            }

            boolean isWeekly() {
                return interval.isWeekly();
            }

            boolean isMonthly() {
                return interval.isMonthly();
            }

            boolean isYearly() {
                return interval.isYearly();
            }

            DayOfWeek weekday() {
                return weekday;
            }

            Month month() {
                return month;
            }

            int day() {
                return day;
            }

            int hour() {
                return hour;
            }

            int minute() {
                return minute;
            }
        }

        private final HostedRepository fromRepo;
        private final Branch fromBranch;
        private final Branch toBranch;
        private final Frequency frequency;
        private final String name;
        private final List<String> dependencies;
        private final List<HostedRepository> prerequisites;

        Spec(HostedRepository fromRepo, Branch fromBranch, Branch toBranch) {
            this(fromRepo, fromBranch, toBranch, null, null, List.of(), List.of());
        }

        Spec(HostedRepository fromRepo, Branch fromBranch, Branch toBranch, String name) {
            this(fromRepo, fromBranch, toBranch, null, name, List.of(), List.of());
        }

        Spec(HostedRepository fromRepo, Branch fromBranch, Branch toBranch, Frequency frequency) {
            this(fromRepo, fromBranch, toBranch, frequency, null, List.of(), List.of());
        }

        Spec(HostedRepository fromRepo,
             Branch fromBranch,
             Branch toBranch,
             Frequency frequency,
             String name,
             List<String> dependencies,
             List<HostedRepository> prerequisites) {
            this.fromRepo = fromRepo;
            this.fromBranch = fromBranch;
            this.toBranch = toBranch;
            this.frequency = frequency;
            this.name = name;
            this.dependencies = dependencies;
            this.prerequisites = prerequisites;
        }

        HostedRepository fromRepo() {
            return fromRepo;
        }

        Branch fromBranch() {
            return fromBranch;
        }

        Branch toBranch() {
            return toBranch;
        }

        Optional<Frequency> frequency() {
            return Optional.ofNullable(frequency);
        }

        Optional<String> name() {
            return Optional.ofNullable(name);
        }

        List<String> dependencies() {
            return dependencies;
        }

        List<HostedRepository> prerequisites() {
            return prerequisites;
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        Files.walk(dir)
             .map(Path::toFile)
             .sorted(Comparator.reverseOrder())
             .forEach(File::delete);
    }

    private Repository cloneAndSyncFork(Path to) throws IOException {
        var repo = pool.materialize(fork, to);

        // Sync personal fork
        var remoteBranches = repo.remoteBranches(target.url().toString());
        for (var branch : remoteBranches) {
            var fetchHead = repo.fetch(target.url(), branch.hash().hex(), false);
            repo.push(fetchHead, fork.url(), branch.name());
        }

        // Must fetch once to update refs/heads
        repo.fetchAll(false);

        return repo;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof MergeBot)) {
            return true;
        }
        var otherBot = (MergeBot) other;
        return !target.name().equals(otherBot.target.name());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        try {
            var sanitizedUrl =
                URLEncoder.encode(fork.webUrl().toString(), StandardCharsets.UTF_8);
            var dir = storage.resolve(sanitizedUrl);

            var repo = cloneAndSyncFork(dir);

            var prTarget = fork.forge().repository(target.name()).orElseThrow(() ->
                    new IllegalStateException("Can't get well-known repository " + target.name())
            );
            var prs = prTarget.pullRequests();
            var currentUser = prTarget.forge().currentUser();

            var unmerged = new HashSet<String>();
            for (var spec : specs) {
                var toBranch = spec.toBranch();
                var fromRepo = spec.fromRepo();
                var fromBranch = spec.fromBranch();

                var targetName = Path.of(target.name()).getFileName();
                var fromName = Path.of(fromRepo.name()).getFileName();
                var fromDesc = targetName.equals(fromName) ? fromBranch.name() : fromName + ":" + fromBranch.name();

                var shouldMerge = true;

                // Check if merge conflict pull request is present
                var title = "Merge " + fromDesc;
                var marker = "<!-- AUTOMATIC MERGE PR -->";
                for (var pr : prs) {
                    if (pr.title().equals(title) &&
                        pr.targetRef().equals(toBranch.name()) &&
                        pr.body().startsWith(marker) &&
                        currentUser.equals(pr.author())) {
                        // Yes, this could be optimized do a merge "this turn", but it is much simpler
                        // to just wait until the next time the bot runs
                        shouldMerge = false;

                        if (pr.labels().contains("ready") && !pr.labels().contains("sponsor")) {
                            var comments = pr.comments();
                            var integrateComments =
                                comments.stream()
                                        .filter(c -> c.author().equals(currentUser))
                                        .filter(c -> c.body().equals(integrationCommand))
                                        .collect(Collectors.toList());
                            if (integrateComments.isEmpty()) {
                                pr.addComment(integrationCommand);
                            } else {
                                var lastIntegrateComment = integrateComments.get(integrateComments.size() - 1);
                                var id = lastIntegrateComment.id();
                                var botUserId = "43336822";
                                var replyMarker = "<!-- Jmerge command reply message (" + id + ") -->";
                                var replies = comments.stream()
                                                      .filter(c -> c.author().id().equals(botUserId))
                                                      .filter(c -> c.body().startsWith(replyMarker))
                                                      .collect(Collectors.toList());
                                if (replies.isEmpty()) {
                                    // No reply yet, just wait
                                } else {
                                    // Got a reply and the "sponsor" label is not present, check for error
                                    // and if we should add the `/integrate` command again
                                    var lastReply = replies.get(replies.size() - 1);
                                    var lines = lastReply.body().split("\n");
                                    var errorPrefix = "@openjdk-bot Your merge request cannot be fulfilled at this time";
                                    if (lines.length > 1 && lines[1].startsWith(errorPrefix)) {
                                        // Try again
                                        pr.addComment(integrationCommand);
                                    }
                                    // Other reply, potentially due to rebase issue, just
                                    // wait for the labeler to add appropriate labels.
                                }
                            }
                        }
                    }
                }

                // Check if merge should happen at this time
                if (spec.frequency().isPresent()) {
                    var now = clock.now();
                    var desc = toBranch.name() + "->" + fromRepo.name() + ":" + fromBranch.name();
                    var freq = spec.frequency().get();
                    if (freq.isHourly()) {
                        if (!hourly.containsKey(desc)) {
                            hourly.put(desc, new HashSet<Integer>());
                        }
                        var minute = now.getMinute();
                        var hour = now.getHour();
                        if (freq.minute() == minute && !hourly.get(desc).contains(hour)) {
                            hourly.get(desc).add(hour);
                        } else {
                            shouldMerge = false;
                        }
                    } else if (freq.isDaily()) {
                        if (!daily.containsKey(desc)) {
                            daily.put(desc, new HashSet<Integer>());
                        }
                        var hour = now.getHour();
                        var day = now.getDayOfYear();
                        if (freq.hour() == hour && !daily.get(desc).contains(day)) {
                            daily.get(desc).add(day);
                        } else {
                            shouldMerge = false;
                        }
                    } else if (freq.isWeekly()) {
                        if (!weekly.containsKey(desc)) {
                            weekly.put(desc, new HashSet<Integer>());
                        }
                        var weekOfYear = now.get(WeekFields.ISO.weekOfYear());
                        var weekday = now.getDayOfWeek();
                        var hour = now.getHour();
                        if (freq.weekday().equals(weekday) &&
                            freq.hour() == hour &&
                            !weekly.get(desc).contains(weekOfYear)) {
                            weekly.get(desc).add(weekOfYear);
                        } else {
                            shouldMerge = false;
                        }
                    } else if (freq.isMonthly()) {
                        if (!monthly.containsKey(desc)) {
                            monthly.put(desc, new HashSet<Month>());
                        }
                        var day = now.getDayOfMonth();
                        var hour = now.getHour();
                        var month = now.getMonth();
                        if (freq.day() == day && freq.hour() == hour &&
                            !monthly.get(desc).contains(month)) {
                            monthly.get(desc).add(month);
                        } else {
                            shouldMerge = false;
                        }
                    } else if (freq.isYearly()) {
                        if (!yearly.containsKey(desc)) {
                            yearly.put(desc, new HashSet<Integer>());
                        }
                        var month = now.getMonth();
                        var day = now.getDayOfMonth();
                        var hour = now.getHour();
                        var year = now.getYear();
                        if (freq.month().equals(month) &&
                            freq.day() == day &&
                            freq.hour() == hour &&
                            !yearly.get(desc).contains(year)) {
                            yearly.get(desc).add(year);
                        } else {
                            shouldMerge = false;
                        }
                    }
                }

                // Check if any prerequisite repository has a conflict pull request open
                if (shouldMerge) {
                    for (var prereq : spec.prerequisites()) {
                        var openMergeConflictPRs = prereq.pullRequests()
                                                         .stream()
                                                         .filter(pr -> pr.title().startsWith("Merge "))
                                                         .filter(pr -> pr.body().startsWith(marker))
                                                         .map(PullRequest::id)
                                                         .collect(Collectors.toList());
                        if (!openMergeConflictPRs.isEmpty()) {
                            log.info("Will not merge because the prerequisite " + prereq.name() +
                                     " has open merge conflicts PRs: " +
                                     String.join(", ", openMergeConflictPRs));
                            shouldMerge = false;
                        }
                    }
                }

                // Check if any dependencies failed
                if (shouldMerge) {
                    if (spec.dependencies().stream().anyMatch(unmerged::contains)) {
                        var failed = spec.dependencies()
                                         .stream()
                                         .filter(unmerged::contains)
                                         .collect(Collectors.toList());
                        log.info("Will not merge because the following dependencies did not merge successfully: " +
                                 String.join(", ", failed));
                        shouldMerge = false;
                    }
                }

                if (!shouldMerge) {
                    log.info("Will not merge " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name());
                    if (spec.name().isPresent()) {
                        unmerged.add(spec.name().get());
                    }
                    continue;
                }

                // Checkout the branch to merge into
                repo.checkout(toBranch, false);
                var remoteBranch = new Branch(repo.upstreamFor(toBranch).orElseThrow(() ->
                    new IllegalStateException("Could not get remote branch name for " + toBranch.name())
                ));
                repo.merge(remoteBranch); // should always be a fast-forward merge
                if (!repo.isClean()) {
                    throw new RuntimeException("Local repository isn't clean after fast-forward merge - has the fork diverged unexpectedly?");
                }

                log.info("Trying to merge " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name());
                log.info("Fetching " + fromRepo.name() + ":" + fromBranch.name());
                var fetchHead = repo.fetch(fromRepo.url(), fromBranch.name(), false);
                var head = repo.resolve(toBranch.name()).orElseThrow(() ->
                        new IOException("Could not resolve branch " + toBranch.name())
                );
                if (repo.contains(toBranch, fetchHead)) {
                    log.info("Nothing to merge");
                    continue;
                }

                var isAncestor = repo.isAncestor(head, fetchHead);

                log.info("Merging into " + toBranch.name());
                IOException error = null;
                try {
                    repo.merge(fetchHead);
                } catch (IOException e) {
                    error = e;
                }

                if (error == null) {
                    log.info("Pushing successful merge");
                    if (!isAncestor) {
                        repo.commit("Automatic merge of " + fromDesc + " into " + toBranch,
                                "duke", "duke@openjdk.org");
                    }
                    try {
                        repo.push(toBranch, target.url().toString(), false);
                    } catch (IOException e) {
                        // A failed push can result in the local and remote branch diverging,
                        // re-create the repository from the remote.
                        // No need to create a pull request, just retry the merge and the push
                        // the next run.
                        deleteDirectory(dir);
                        repo = cloneAndSyncFork(dir);
                    }
                } else {
                    if (spec.name().isPresent()) {
                        unmerged.add(spec.name().get());
                    }
                    log.info("Got error: " + error.getMessage());
                    log.info("Aborting unsuccesful merge");
                    var status = repo.status();
                    repo.abortMerge();

                    var fromRepoName = Path.of(fromRepo.webUrl().getPath()).getFileName();

                    var numBranchesInFork = repo.remoteBranches(fork.webUrl().toString()).size();
                    var branchDesc = Integer.toString(numBranchesInFork + 1);
                    repo.push(fetchHead, fork.url(), branchDesc);

                    log.info("Creating pull request to alert");
                    var mergeBase = repo.mergeBase(fetchHead, head);

                    var message = new ArrayList<String>();
                    message.add(marker);
                    message.add("<!-- " + fetchHead.hex() + " -->");

                    var commits = repo.commitMetadata(mergeBase.hex() + ".." + fetchHead.hex(), true);
                    var numCommits = commits.size();
                    var are = numCommits > 1 ? "are" : "is";
                    var s = numCommits > 1 ? "s" : "";

                    message.add("Hi all,");
                    message.add("");
                    message.add("this is an _automatically_ generated pull request to notify you that there " +
                                are + " " + numCommits + " commit" + s + " from the branch `" + fromDesc + "`" +
                                "that can **not** be merged into the branch `" + toBranch.name() + "`:");

                    message.add("");
                    var unmergedFiles = status.stream().filter(entry -> entry.status().isUnmerged()).collect(Collectors.toList());
                    if (unmergedFiles.size() <= 10) {
                        var files = unmergedFiles.size() > 1 ? "files" : "file";
                        message.add("The following " + files + " contains merge conflicts:");
                        message.add("");
                        for (var fileStatus : unmergedFiles) {
                            message.add("- " + fileStatus.source().path().orElseThrow());
                        }
                    } else {
                        message.add("Over " + unmergedFiles.size() + " files contains merge conflicts.");
                    }
                    message.add("");

                    var project = JCheckConfiguration.from(repo, head).map(conf -> conf.general().project());
                    if (project.isPresent()) {
                        message.add("All Committers in this [project](https://openjdk.java.net/census#" + project + ") " +
                                    "have access to my [personal fork](" + fork.nonTransformedWebUrl() + ") and can " +
                                    "therefore help resolve these merge conflicts (you may want to coordinate " +
                                    "who should do this).");
                    } else {
                        message.add("All users with access to my [personal fork](" + fork.nonTransformedWebUrl() + ") " +
                                    "can help resolve these merge conflicts " +
                                    "(you may want to coordinate who should do this).");
                    }
                    message.add("The following paragraphs will give an example on how to solve these " +
                                "merge conflicts and push the resulting merge commit to this pull request.");
                    message.add("The below commands should be run in a local clone of your " +
                                "[personal fork](https://wiki.openjdk.java.net/display/skara#Skara-Personalforks) " +
                                "of the [" + target.name() + "](" + target.nonTransformedWebUrl() + ") repository.");
                    message.add("");
                    var localBranchName = "openjdk-bot-" + branchDesc;
                    message.add("```bash");
                    message.add("# Ensure target branch is up to date");
                    message.add("$ git checkout " + toBranch.name());
                    message.add("$ git pull " + target.nonTransformedWebUrl() + " " + toBranch.name());
                    message.add("");
                    message.add("# Fetch and checkout the branch for this pull request");
                    message.add("$ git fetch " + fork.nonTransformedWebUrl() + " +" + branchDesc + ":" + localBranchName);
                    message.add("$ git checkout " + localBranchName);
                    message.add("");
                    message.add("# Merge the target branch");
                    message.add("$ git merge " + toBranch.name());
                    message.add("```");
                    message.add("");
                    message.add("When you have resolved the conflicts resulting from the `git merge` command " +
                                "above, run the following commands to create a merge commit:");
                    message.add("");
                    message.add("```bash");
                    message.add("$ git add paths/to/files/with/conflicts");
                    message.add("$ git commit -m 'Merge " + fromDesc + "'");
                    message.add("```");
                    message.add("");
                    message.add("");
                    message.add("When you have created the merge commit, run the following command to push the merge commit " +
                                "to this pull request:");
                    message.add("");
                    message.add("```bash");
                    message.add("$ git push " + fork.nonTransformedWebUrl() + " " + localBranchName + ":" + branchDesc);
                    message.add("```");
                    message.add("");
                    message.add("_Note_: if you are using SSH to push commits to GitHub, then change the URL in the above `git push` command accordingly.");
                    message.add("");
                    message.add("Thanks,");
                    message.add("J. Duke");

                    var prFromFork = fork.createPullRequest(prTarget,
                                                            toBranch.name(),
                                                            branchDesc,
                                                            title,
                                                            message);
                    var prFromTarget = target.pullRequest(prFromFork.id());
                    prFromTarget.addLabel("failed-auto-merge");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "MergeBot@(" + target.name() + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
