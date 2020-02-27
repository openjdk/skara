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
package org.openjdk.skara.cli.pr;

import org.openjdk.skara.args.*;
import org.openjdk.skara.host.HostUser;

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GitPrList {
    private static int longest(List<String> strings) {
        return strings.stream().mapToInt(String::length).max().orElse(0);
    }

    public static void main(String[] args) throws IOException {
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
                  .fullname("no-token")
                  .helptext("Do not use a personal access token (PAT)")
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

        var inputs = List.of(
            Input.position(0)
                 .describe("ID")
                 .singular()
                 .optional()
        );

        var parser = new ArgumentParser("git-pr", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
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
        var urls = new ArrayList<String>();
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

        var columnTitles = List.of("id", "title", "authors", "assignees", "labels", "issues", "branch", "status", "url");
        var columnValues = Map.of(columnTitles.get(0), ids,
                                  columnTitles.get(1), titles,
                                  columnTitles.get(2), authors,
                                  columnTitles.get(3), assignees,
                                  columnTitles.get(4), labels,
                                  columnTitles.get(5), issues,
                                  columnTitles.get(6), branches,
                                  columnTitles.get(7), statuses,
                                  columnTitles.get(8), urls);
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
            urls.add(pr.webUrl().toString());

            if (pr.sourceRepository().webUrl().equals(uri)) {
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
    }
}
