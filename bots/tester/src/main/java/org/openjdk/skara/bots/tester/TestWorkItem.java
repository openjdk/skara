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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.ci.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.*;

public class TestWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final ContinuousIntegration ci;
    private final String approversGroupId;
    private final List<String> availableJobs;
    private final List<String> defaultJobs;
    private final String name;
    private final Path storage;
    private final HostedRepository repository;
    private final PullRequest pr;

    TestWorkItem(ContinuousIntegration ci, String approversGroupId, List<String> availableJobs,
                 List<String> defaultJobs, String name, Path storage, PullRequest pr) {
        this.ci = ci;
        this.approversGroupId = approversGroupId;
        this.availableJobs = availableJobs;
        this.defaultJobs = defaultJobs;
        this.name = name;
        this.storage = storage;
        this.repository = pr.repository();
        this.pr = pr;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof TestWorkItem)) {
            return true;
        }
        var o = (TestWorkItem) other;
        if (!repository.url().equals(o.repository.url())) {
            return true;
        }
        return !pr.id().equals(o.pr.id());
    }


    private String jobId(State state) {
        var host = repository.webUrl().getHost();
        return host + "-" +
               Long.toString(repository.id()) + "-"+
               pr.id() + "-" +
               state.requested().id();
    }


    private String osDisplayName(Build.OperatingSystem os) {
        switch (os) {
            case WINDOWS:
                return "Windows";
            case MACOS:
                return "macOS";
            case LINUX:
                return "Linux";
            case SOLARIS:
                return "Solaris";
            case AIX:
                return "AIX";
            case FREEBSD:
                return "FreeBSD";
            case OPENBSD:
                return "OpenBSD";
            case NETBSD:
                return "NetBSD";
            case HPUX:
                return "HP-UX";
            case HAIKU:
                return "Haiku";
            default:
                throw new IllegalArgumentException("Unknown operating system: " + os.toString());
        }
    }

    private String cpuDisplayName(Build.CPU cpu) {
        switch (cpu) {
            case X86:
                return "x86";
            case X64:
                return "x64";
            case SPARCV9:
                return "SPARC V9";
            case AARCH64:
                return "AArch64";
            case AARCH32:
                return "AArch32";
            case PPCLE32:
                return "PPC LE 32";
            case PPCLE64:
                return "PPC LE 64";
            default:
                throw new IllegalArgumentException("Unknown cpu: " + cpu.toString());
        }
    }

    private String debugLevelDisplayName(Build.DebugLevel level) {
        switch (level) {
            case RELEASE:
                return "release";
            case FASTDEBUG:
                return "fastdebug";
            case SLOWDEBUG:
                return "slowdebug";
            default:
                throw new IllegalArgumentException("Unknown debug level: " + level.toString());
        }
    }

    private void appendIdSection(StringBuilder summary, Job job) {
        summary.append("## Id");
        summary.append("\n");

        summary.append("`");
        summary.append(job.id());
        summary.append("`");
        summary.append("\n");
    }

    private void appendBuildsSection(StringBuilder summary, Job job) {
        var perOSandArch = new HashMap<String, List<String>>();
        for (var build : job.builds()) {
            var osAndArch = osDisplayName(build.os()) + " " + cpuDisplayName(build.cpu());
            var debugLevel = debugLevelDisplayName(build.debugLevel());
            if (!perOSandArch.containsKey(osAndArch)) {
                perOSandArch.put(osAndArch, new ArrayList<String>());
            }
            perOSandArch.get(osAndArch).add(debugLevel);
        }

        summary.append("## Builds");
        summary.append("\n");

        for (var key : perOSandArch.keySet()) {
            summary.append("- ");
            summary.append(key);
            summary.append(" (");
            summary.append(String.join(",", perOSandArch.get(key)));
            summary.append(")");
            summary.append("\n");
        }
    }

    private void appendTestsSection(StringBuilder summary, Job job) {
        summary.append("## Tests");
        summary.append("\n");

        for (var test : job.tests()) {
            summary.append("- ");
            summary.append(test.name());
            summary.append("\n");
        }
    }

    private void appendStatusSection(StringBuilder summary, Job job) {
        var s = job.status();
        summary.append("## Status");
        summary.append("\n");

        var numCompleted = s.numCompleted();
        summary.append(Integer.toString(numCompleted));
        summary.append(numCompleted == 1 ? " job " : " jobs ");
        summary.append("completed, ");

        var numRunning = s.numRunning();
        summary.append(Integer.toString(numRunning));
        summary.append(numRunning == 1 ? " job " : " jobs ");
        summary.append("running, ");

        var numNotStarted = s.numNotStarted();
        summary.append(Integer.toString(numNotStarted));
        summary.append(numNotStarted == 1 ? " job " : " jobs ");
        summary.append("not yet started");
        summary.append("\n");
    }

    private void appendResultSection(StringBuilder summary, Job job) {
        var r = job.result();
        summary.append("## Result");
        summary.append("\n");

        var numPassed = r.numPassed();
        summary.append(Integer.toString(numPassed));
        summary.append(numPassed == 1 ? " job " : " jobs ");
        summary.append("passed, ");

        var numFailed = r.numFailed();
        summary.append(Integer.toString(numFailed));
        summary.append(numFailed == 1 ? " job " : " jobs ");
        summary.append("with failures, ");

        var numSkipped = r.numSkipped();
        summary.append(Integer.toString(numSkipped));
        summary.append(numSkipped == 1 ? " job " : " jobs ");
        summary.append("not run");
        summary.append("\n");
    }

    private String display(Job job) {
        var sb = new StringBuilder();
        appendIdSection(sb, job);
        sb.append("\n");
        appendBuildsSection(sb, job);
        sb.append("\n");
        appendTestsSection(sb, job);
        sb.append("\n");
        appendStatusSection(sb, job);
        sb.append("\n");
        if (job.isCompleted()) {
            appendResultSection(sb, job);
        }
        return sb.toString();
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var state = State.from(pr, approversGroupId);
        var stage = state.stage();
        if (stage == Stage.NA || stage == Stage.ERROR || stage == Stage.PENDING || stage == Stage.FINISHED) {
            // nothing to do
            return List.of();
        }

        if (stage == Stage.STARTED) {
            if (state.started() != null) {
                var lines = state.started().body().split("\n");
                var jobId = lines[1].replace("<!-- ", "").replace(" -->", "");
                var hash = lines[2].replace("<!-- ", "").replace(" -->", "");

                try {
                    var job = ci.job(jobId);
                    var checks = pr.checks(new Hash(hash));
                    if (checks.containsKey(name)) {
                        var check = checks.get(name);
                        if (check.status() == CheckStatus.IN_PROGRESS) {
                            var builder = CheckBuilder.from(check);
                            if (job.isCompleted()) {
                                var success = job.result().numFailed() == 0 &&
                                              job.result().numSkipped() == 0;
                                builder = builder.complete(success);
                                var requestor = state.requested().author().userName();
                                var commentLines = List.of(
                                        "<!-- TEST FINISHED -->",
                                        "<!-- " + jobId + " -->",
                                        "<!-- " + hash + " -->",
                                        "@" + requestor + " your test job with id " + jobId + " for commits up until " + hash.substring(0, 8) + " has finished."
                                );
                                pr.addComment(String.join("\n", commentLines));
                            }
                            builder = builder.summary(display(job));
                            pr.updateCheck(builder.build());
                        }
                    } else {
                        log.warning("Could not find check for job with " + jobId + " for hash " + hash + " for PR " + pr.webUrl());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                log.warning("No 'started' comment present for PR " + pr.webUrl());
            }
        } else if (stage == stage.CANCELLED) {
            if (state.started() != null) {
                var lines = state.started().body().split("\n");
                var jobId = lines[1].replace("<!-- ", "").replace(" -->", "");
                var hash = lines[2].replace("<!-- ", "").replace(" -->", "");

                try {
                    ci.cancel(jobId);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                var checks = pr.checks(new Hash(hash));
                if (checks.containsKey(name)) {
                    var check = checks.get(name);
                    if (check.status() != CheckStatus.CANCELLED) {
                        var builder = CheckBuilder.from(check);
                        var newCheck = builder.cancel()
                                              .build();
                        pr.updateCheck(newCheck);
                    }
                } else {
                    log.warning("Could not find check for job with " + jobId + " for hash " + hash + " for PR " + pr.webUrl());
                }
            }
        } else if (stage == Stage.REQUESTED) {
            var requestedJobs = state.requested().body().substring("/test".length());
            if (requestedJobs.trim().isEmpty()) {
                requestedJobs = String.join(",", defaultJobs);
            }
            var trimmedJobs = Stream.of(requestedJobs.split(",")).map(String::trim).collect(Collectors.toList());
            var nonExistingJobs = trimmedJobs.stream().filter(s -> !availableJobs.contains(s))
                                                      .collect(Collectors.toList());
            if (!nonExistingJobs.isEmpty()) {
                var wording = nonExistingJobs.size() == 1 ? "group " : "groups ";
                var lines = List.of(
                   "<!-- TEST ERROR -->",
                   "@" + state.requested().author().userName() + " the test " + wording + String.join(",", nonExistingJobs) + " does not exist"
                );
                pr.addComment(String.join("\n", lines));
            } else {
                var head = pr.headHash();
                var lines = List.of(
                        "<!-- TEST PENDING -->",
                        "<!-- " + head.hex() + " -->",
                        "<!-- " + String.join(",", trimmedJobs) + " -->",
                        "@" + state.requested().author().userName() + " you need to get approval to run the tests in " +
                        String.join(",", trimmedJobs) + " for commits up until " + head.abbreviate()
                );
                pr.addComment(String.join("\n", lines));
            }
        } else if (stage == Stage.APPROVED) {
            Hash head = null;
            List<String> jobs = null;

            if (state.pending() != null) {
                var comment = state.pending();
                var body = comment.body().split("\n");

                head = new Hash(body[1].replace("<!-- ", "").replace(" -->", ""));
                var requestedJobs = body[2].replace("<!-- ", "").replace(" -->", "");
                jobs = Arrays.asList(requestedJobs.split(","));
            } else {
                var comment = state.requested();
                var body = comment.body().split("\n");

                head = pr.headHash();
                var requestedJobs = state.requested().body().substring("/test".length());
                if (requestedJobs.trim().isEmpty()) {
                    requestedJobs = String.join(",", defaultJobs);
                }
                var trimmedJobs = Stream.of(requestedJobs.split(",")).map(String::trim).collect(Collectors.toList());
                var nonExistingJobs = trimmedJobs.stream().filter(s -> !availableJobs.contains(s))
                                                          .collect(Collectors.toList());
                if (!nonExistingJobs.isEmpty()) {
                    var wording = nonExistingJobs.size() == 1 ? "group " : "groups ";
                    var lines = List.of(
                       "<!-- TEST ERROR -->",
                       "@" + state.requested().author().userName() + " the test " + wording + String.join(",", nonExistingJobs) + " does not exist"
                    );
                    pr.addComment(String.join("\n", lines));
                    return List.of();
                }

                jobs = trimmedJobs;
            }
            var jobId = jobId(state);

            Job job = null;
            Hash fetchHead = null;
            try {
                var sanitizedUrl = URLEncoder.encode(repository.webUrl().toString(), StandardCharsets.UTF_8);
                var localRepoDir = storage.resolve("mach5-bot")
                                          .resolve(sanitizedUrl)
                                          .resolve(pr.id());
                var host = repository.webUrl().getHost();
                Repository localRepo = null;
                if (!Files.exists(localRepoDir)) {
                    log.info("Cloning " + repository.name());
                    Files.createDirectories(localRepoDir);
                    var url = repository.webUrl().toString();
                    if (!url.endsWith(".git")) {
                        url += ".git";
                    }
                    localRepo = Repository.clone(URI.create(url), localRepoDir);
                } else {
                    log.info("Found existing scratch directory for " + repository.name());
                    localRepo = Repository.get(localRepoDir).orElseThrow(() -> {
                            return new RuntimeException("Repository in " + localRepoDir + " has vanished");
                    });
                }
                fetchHead = localRepo.fetch(repository.url(), pr.headHash().hex(), false);
                localRepo.checkout(fetchHead, true);
                job = ci.submit(localRepoDir, jobs, jobId);
            } catch (IOException e) {
                var lines = List.of(
                        "<!-- TEST ERROR -->",
                        "Could not create test job"
                );
                pr.addComment(String.join("\n", lines));

                throw new UncheckedIOException(e);
            }

            var check = CheckBuilder.create(name, fetchHead)
                                    .title("Summary")
                                    .summary(display(job))
                                    .metadata(jobId)
                                    .build();
            pr.createCheck(check);

            var lines = List.of(
                    "<!-- TEST STARTED -->",
                    "<!-- " + jobId + " -->",
                    "<!-- " + fetchHead.hex() + " -->",
                    "A test job has been started with id: " + jobId
            );
            pr.addComment(String.join("\n", lines));
        } else {
            throw new RuntimeException("Unexpected state " + state);
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "TestWorkItem@" + pr.repository().name() + "#" + pr.id();
    }
}
