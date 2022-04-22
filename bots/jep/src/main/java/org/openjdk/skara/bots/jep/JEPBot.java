/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.bots.jep;

import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.IssueProject;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openjdk.skara.bots.jep.JEPBotFactory.NAME;

public class JEPBot implements Bot, WorkItem {
    final static String JEP_LABEL = "jep";
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");
    private final static Pattern jepMarkerPattern = Pattern.compile("<!-- jep: '(.*?)' '(.*?)' '(.*?)' -->");
    private final HostedRepository repo;
    private final IssueProject issueProject;

    JEPBot(HostedRepository repo, IssueProject issueProject) {
        this.repo = repo;
        this.issueProject = issueProject;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof JEPBot otherBot)) {
            return true;
        }
        return !repo.isSame(otherBot.repo);
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var prs = repo.pullRequests();
        for (var pr : prs) {
            var jepComment = pr.comments().stream()
                    .filter(comment -> comment.author().equals(pr.repository().forge().currentUser()))
                    .flatMap(comment -> comment.body().lines())
                    .map(jepMarkerPattern::matcher)
                    .filter(Matcher::find)
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (jepComment == null) {
                log.fine("No jep command found in comment for " + describe(pr));
                if (pr.labelNames().contains(JEP_LABEL)) {
                    log.info("Removing JEP label from " + describe(pr));
                    pr.removeLabel(JEP_LABEL);
                }
                continue;
            }

            var issueId = jepComment.group(2);
            if ("unneeded".equals(issueId)) {
                log.info("Found `/jep unneeded` command for " + describe(pr));
                if (pr.labelNames().contains(JEP_LABEL)) {
                    log.info("Removing JEP label from " + describe(pr));
                    pr.removeLabel(JEP_LABEL);
                }
                continue;
            }

            var issueOpt = issueProject.issue(issueId);
            if (issueOpt.isEmpty()) {
                log.severe("The issue `" + issueId + "` for " + describe(pr) + " doesn't exist.");
                continue;
            }
            var issue = issueOpt.get();

            var issueType = issue.properties().get("issuetype");
            if (issueType == null || !"JEP".equals(issueType.asString())) {
                log.severe("The issue `" + issue.id() + "` for " + describe(pr) + " is not a JEP.");
                continue;
            }

            var issueStatus = issue.properties().get("status").get("name").asString();
            var resolution = issue.properties().get("resolution");
            String resolutionName = "";
            if (resolution != null && !resolution.isNull() &&
                    resolution.get("name") != null && !resolution.get("name").isNull()) {
                resolutionName = resolution.get("name").asString();
            }

            var hasTargeted = "Targeted".equals(issueStatus) ||
                    "Integrated".equals(issueStatus) ||
                    "Completed".equals(issueStatus) ||
                    ("Closed".equals(issueStatus) && "Delivered".equals(resolutionName));
            if (hasTargeted && pr.labelNames().contains(JEP_LABEL)) {
                log.info("JEP issue " + issue.id() + " found in state " + issueStatus + ", removing JEP label from " + describe(pr));
                pr.removeLabel(JEP_LABEL);
            } else if (!hasTargeted && !pr.labelNames().contains(JEP_LABEL)) {
                log.info("JEP issue " + issue.id() + " found in state " + issueStatus + ", adding JEP label to " + describe(pr));
                pr.addLabel(JEP_LABEL);
            }
        }
        return List.of();
    }

    private String describe(PullRequest pr) {
        return repo.name() + "#" + pr.id();
    }

    @Override
    public String botName() {
        return name();
    }

    @Override
    public String workItemName() {
        return name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }

    @Override
    public String name() {
        return NAME;
    }
}
