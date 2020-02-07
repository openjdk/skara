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

import java.io.IOException;
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
import java.util.logging.Logger;

class MergeBot implements Bot, WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final Path storage;

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

        Spec(HostedRepository fromRepo, Branch fromBranch, Branch toBranch) {
            this(fromRepo, fromBranch, toBranch, null);
        }

        Spec(HostedRepository fromRepo, Branch fromBranch, Branch toBranch, Frequency frequency) {
            this.fromRepo = fromRepo;
            this.fromBranch = fromBranch;
            this.toBranch = toBranch;
            this.frequency = frequency;
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
    public void run(Path scratchPath) {
        try {
            var sanitizedUrl =
                URLEncoder.encode(fork.webUrl().toString(), StandardCharsets.UTF_8);
            var dir = storage.resolve(sanitizedUrl);

            Repository repo = null;
            if (!Files.exists(dir)) {
                log.info("Cloning " + fork.name());
                Files.createDirectories(dir);
                repo = Repository.clone(fork.url(), dir);
            } else {
                log.info("Found existing scratch directory for " + fork.name());
                repo = Repository.get(dir).orElseThrow(() -> {
                        return new RuntimeException("Repository in " + dir + " has vanished");
                });
            }

            // Sync personal fork
            var remoteBranches = repo.remoteBranches(target.url().toString());
            for (var branch : remoteBranches) {
                var fetchHead = repo.fetch(target.url(), branch.hash().hex());
                repo.push(fetchHead, fork.url(), branch.name());
            }

            // Must fetch once to update refs/heads
            repo.fetchAll();

            var prs = target.pullRequests();
            var currentUser = target.forge().currentUser();

            for (var spec : specs) {
                var toBranch = spec.toBranch();
                var fromRepo = spec.fromRepo();
                var fromBranch = spec.fromBranch();

                log.info("Deciding whether to merge " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name());

                // Checkout the branch to merge into
                repo.checkout(toBranch, false);
                var remoteBranch = new Branch(repo.upstreamFor(toBranch).orElseThrow(() ->
                    new IllegalStateException("Could not get remote branch name for " + toBranch.name())
                ));
                repo.merge(remoteBranch); // should always be a fast-forward merge

                // Check if merge conflict pull request is present
                var shouldMerge = true;
                var title = "Cannot automatically merge " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name();
                var marker = "<!-- MERGE CONFLICTS -->";
                for (var pr : prs) {
                    if (pr.title().equals(title) &&
                        pr.body().startsWith(marker) &&
                        currentUser.equals(pr.author())) {
                        var lines = pr.body().split("\n");
                        var head = new Hash(lines[1].substring(5, 45));
                        if (repo.contains(toBranch, head)) {
                            log.info("Closing resolved merge conflict PR " + pr.id() + ", will try merge");
                            pr.addComment("Merge conflicts have been resolved, closing this PR");
                            pr.setState(PullRequest.State.CLOSED);
                        } else {
                            log.info("Outstanding unresolved merge already present, will not merge");
                            shouldMerge = false;
                        }
                        break;
                    }
                }

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

                if (!shouldMerge) {
                    log.info("Will not merge " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name());
                    continue;
                }

                log.info("Merging " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name());
                log.info("Fetching " + fromRepo.name() + ":" + fromBranch.name());
                var fetchHead = repo.fetch(fromRepo.url(), fromBranch.name());
                var head = repo.resolve(toBranch.name()).orElseThrow(() ->
                        new IOException("Could not resolve branch " + toBranch.name())
                );
                if (repo.contains(toBranch, fetchHead)) {
                    log.info("Nothing to merge");
                    continue;
                }

                var isAncestor = repo.isAncestor(head, fetchHead);

                log.info("Trying to merge into " + toBranch.name());
                IOException error = null;
                try {
                    repo.merge(fetchHead);
                } catch (IOException e) {
                    error = e;
                }

                if (error == null) {
                    log.info("Pushing successful merge");
                    if (!isAncestor) {
                        var targetName = Path.of(target.name()).getFileName();
                        var fromName = Path.of(fromRepo.name()).getFileName();
                        var fromDesc = targetName.equals(fromName) ? fromBranch : fromName + ":" + fromBranch;
                        repo.commit("Automatic merge of " + fromDesc + " into " + toBranch,
                                "duke", "duke@openjdk.org");
                    }
                    repo.push(toBranch, target.url().toString(), false);
                } else {
                    log.info("Got error: " + error.getMessage());
                    log.info("Aborting unsuccesful merge");
                    repo.abortMerge();

                    var fromRepoName = Path.of(fromRepo.webUrl().getPath()).getFileName();
                    var branchDesc = fromRepoName + "/" + fromBranch.name() + "->" + toBranch.name();
                    repo.push(fetchHead, fork.url(), branchDesc, true);

                    log.info("Creating pull request to alert");
                    var mergeBase = repo.mergeBase(fetchHead, head);
                    var commits = repo.commits(mergeBase.hex() + ".." + fetchHead.hex(), true).asList();

                    var message = new ArrayList<String>();
                    message.add(marker);
                    message.add("<!-- " + fetchHead.hex() + " -->");
                    message.add("The following commits from `" + fromRepo.name() + ":" + fromBranch.name() +
                                "` could *not* be automatically merged into `" + toBranch.name() + "`:");
                    message.add("");
                    for (var commit : commits) {
                        message.add("- " + commit.hash().abbreviate() + ": " + commit.message().get(0));
                    }
                    message.add("");
                    message.add("To manually resolve these merge conflicts, please create a personal fork of " +
                                target.webUrl() + " and execute the following commands:");
                    message.add("");
                    message.add("```bash");
                    message.add("$ git checkout " + toBranch.name());
                    message.add("$ git pull " + fromRepo.webUrl() + " " + fromBranch.name());
                    message.add("```");
                    message.add("");
                    message.add("When you have resolved the conflicts resulting from the above commands, run:");
                    message.add("");
                    message.add("```bash");
                    message.add("$ git add paths/to/files/with/conflicts");
                    message.add("$ git commit -m 'Merge'");
                    message.add("```");
                    message.add("");
                    message.add("Push the resolved merge conflict to your personal fork and " +
                                "create a pull request towards this repository.");
                    message.add("");
                    message.add("This pull request will be closed automatically by a bot once " +
                                "the merge conflicts have been resolved.");
                    fork.createPullRequest(target,
                                           toBranch.name(),
                                           branchDesc,
                                           title,
                                           message);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
