/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.cli.debug;

import org.openjdk.skara.args.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jbs.*;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.net.URI;
import java.util.*;

public class IssueRedecorate {
    static final List<Flag> flags = List.of(
            Switch.shortcut("u")
                  .fullname("url")
                  .helptext("Alternative JBS URL (defaults to https://bugs.openjdk.java.net)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional());

    static final List<Input> inputs = List.of(
            Input.position(0)
                 .describe("issue ID")
                 .singular()
                 .required()
            );

    public static void main(String[] args) throws IOException {
        var parser = new ArgumentParser("git issue-redecorate", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-issue-redecorate version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        IssueTracker issueTracker = null;
        var issueTrackerURI = URI.create(arguments.get("url").orString("https://bugs.openjdk.java.net"));
        var issueTrackerFactories = IssueTrackerFactory.getIssueTrackerFactories();
        for (var issueTrackerFactory : issueTrackerFactories) {
            var tracker = issueTrackerFactory.create(issueTrackerURI, null, null);
            if (tracker.isValid()) {
                issueTracker = tracker;
            }
        }
        if (issueTracker == null) {
            System.out.println("Failed to create an issue tracker instance for " + issueTrackerURI);
            System.exit(1);
        }

        var issueProject = issueTracker.project("JDK");
        org.openjdk.skara.issuetracker.Issue issue = issueProject.issue(arguments.at(0).asString()).orElseThrow();

        var mainIssue = Backports.findMainIssue(issue);
        if (mainIssue.isEmpty()) {
            System.out.println("No corresponding main issue found");
            System.exit(0);
        }
        System.out.println("Looking at " + arguments.at(0).asString() + " - main issue is " + mainIssue.get().id());

        var related = Backports.findBackports(mainIssue.get(), true);
        var allIssues = new ArrayList<Issue>();
        allIssues.add(mainIssue.get());
        allIssues.addAll(related);

        var needsLabel = new HashSet<>(Backports.releaseStreamDuplicates(allIssues));
        for (var i : allIssues) {
            var version = Backports.mainFixVersion(i);
            var versionString = version.map(JdkVersion::raw).orElse("no fix version");
            if (needsLabel.contains(i)) {
                if (i.labels().contains("hgupdate-sync")) {
                    System.out.println("✔️ " + i.id() + " (" + versionString + ") - already labeled");
                } else {
                    System.out.println("⏳ " + i.id() + " (" + versionString + ") - needs to be labeled");
                }
            } else {
                if (i.labels().contains("hgupdate-sync")) {
                    System.out.println("❌ " + i.id() + " (" + versionString + ") - labeled incorrectly");
                } else {
                    System.out.println("✔️ " + i.id() + " (" + versionString + ") - not labeled");
                }
            }
        }
    }
}
