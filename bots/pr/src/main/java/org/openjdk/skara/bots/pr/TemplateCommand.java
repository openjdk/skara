/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.VCS;

import java.io.PrintWriter;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.openjdk.skara.bots.common.PullRequestConstants.PROGRESS_MARKER;
import static org.openjdk.skara.bots.common.CommandNameEnum.template;

public class TemplateCommand implements CommandHandler {
    private static final URI PR_TEMPLATE_DOC =
        URI.create("https://docs.github.com/en/communities/" +
                   "using-templates-to-encourage-useful-issues-and-pull-requests/" +
                   "about-issue-and-pull-request-templates#pull-request-templates");

    private static final String GITHUB_PR_TEMPLATE_PATH = ".github/pull_request_template.md";
    private static final String GITLAB_PR_TEMPLATE_PATH = ".gitlab/merge_request_templates/default.md";

    private static final String GIT_DEFAULT_BRANCH = Branch.defaultFor(VCS.GIT).name();


    @Override
    public String description() {
        return "Appends a [pull request template](" + PR_TEMPLATE_DOC + ") to the pull request body.";
    }

    @Override
    public String name() {
        return template.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }

    private static Optional<String> getPullRequestTemplate(HostedRepository repo) {
        // Only load templates from the "master" branch (not from the PR target branch)

        var template = repo.fileContents(GITHUB_PR_TEMPLATE_PATH, GIT_DEFAULT_BRANCH);
        if (template.isPresent()) {
            return template;
        }

        template = repo.fileContents(GITLAB_PR_TEMPLATE_PATH, GIT_DEFAULT_BRANCH);
        if (template.isPresent()) {
            return template;
        }

        return repo.forge().defaultPullRequestTemplate();
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply)
    {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the pull request author can append a pull request template");
            return;
        }

        if (command.args().isEmpty()) {
            reply.println("Missing command 'append', usage: `/template append`");
            return;
        }
        if (!command.args().equals("append")) {
            reply.println("Unknown argument '" + command.args() + "', usage: `/template append`");
            return;
        }

        var repo = pr.repository();
        var template = getPullRequestTemplate(repo);
        if (template.isEmpty()) {
            reply.println("This repository does not have a pull request template");
            return;
        }

        // Retrieve the body again here to lower the chance of concurrent updates
        var body = repo.pullRequest(pr.id()).body();
        var markerIndex = body.lastIndexOf(PROGRESS_MARKER);
        var userBody = markerIndex == -1 ? body : body.substring(0, markerIndex).stripTrailing();
        var newBody = userBody + "\n\n" + template.get();
        if (markerIndex != -1) {
            newBody += "\n\n" + body.substring(markerIndex);
        }

        pr.setBody(newBody);
        reply.println("The pull request template has been appended to the pull request body");
    }
}
