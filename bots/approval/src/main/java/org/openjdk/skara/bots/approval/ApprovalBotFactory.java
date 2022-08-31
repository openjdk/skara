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
package org.openjdk.skara.bots.approval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bot.BotFactory;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.IssueProject;

public class ApprovalBotFactory implements BotFactory {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.approval");
    static final String NAME = "approval";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var botList = new ArrayList<Bot>();
        var specific = configuration.specific();
        var issueProjects = new HashSet<IssueProject>();
        var repositories = new HashMap<IssueProject, List<HostedRepository>>();
        var approvalInfoMap = new HashMap<IssueProject, List<ApprovalInfo>>();

        for (var project : specific.get("projects").asArray()) {
            var repo = configuration.repository(project.get("repository").asString());
            var issueProject = configuration.issueProject(project.get("issues").asString());
            issueProjects.add(issueProject);
            if (!approvalInfoMap.containsKey(issueProject)) {
                approvalInfoMap.put(issueProject, new ArrayList<>());
            }
            if (!repositories.containsKey(issueProject)) {
                repositories.put(issueProject, new ArrayList<>());
            }
            repositories.get(issueProject).add(repo);

            var approvalInfoList = new ArrayList<ApprovalInfo>();
            for (var branchInfo : project.get("approval").asArray()) {
                var requestLabel = branchInfo.get("request-label").asString();
                var approvalLabel = branchInfo.get("approval-label").asString();
                var disapprovalLabel = branchInfo.get("disapproval-label").asString();
                var maintainers = new HashSet<String>();
                if (branchInfo.contains("maintainers")) {
                    for (var maintainer : branchInfo.get("maintainers").asArray()) {
                        maintainers.add(maintainer.asString());
                    }
                }
                approvalInfoList.add(new ApprovalInfo(repo, Pattern.compile(branchInfo.get("branch").asString()),
                        requestLabel, approvalLabel, disapprovalLabel, maintainers));
            }
            approvalInfoMap.get(issueProject).addAll(approvalInfoList);
            var pullRequestBot = new ApprovalPullRequestBot(repo, issueProject, approvalInfoList);
            log.info("Setting up approval pull request bot for " + repo.name());
            botList.add(pullRequestBot);
        }

        for (var issueProject : issueProjects) {
            log.info("Setting up approval issue bot for " + issueProject.name());
            botList.add(new ApprovalIssueBot(issueProject, repositories.get(issueProject), approvalInfoMap.get(issueProject)));
        }

        return botList;
    }
}
