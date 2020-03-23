/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.ini.INI;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class JCheckConfiguration {
    private GeneralConfiguration general;
    private RepositoryConfiguration repository;
    private CensusConfiguration census;
    private ChecksConfiguration checks;

    private JCheckConfiguration(INI ini) {
        general = GeneralConfiguration.parse(ini.section(GeneralConfiguration.name()));
        if (general.project() == null) {
            throw new IllegalArgumentException("general.project must be specified");
        }
        repository = RepositoryConfiguration.parse(ini.section(RepositoryConfiguration.name()));
        census = CensusConfiguration.parse(ini.section(CensusConfiguration.name()));
        checks = ChecksConfiguration.parse(ini.section(ChecksConfiguration.name()));
    }

    public GeneralConfiguration general() {
        return general;
    }

    public RepositoryConfiguration repository() {
        return repository;
    }

    public CensusConfiguration census() {
        return census;
    }

    public ChecksConfiguration checks() {
        return checks;
    }

    private static INI convert(INI old) {
        var project = old.get("project").asString();
        if (project == null) {
            throw new IllegalArgumentException("'project' must be specified");
        }

        var config = new ArrayList<String>();
        config.add("[general]");
        config.add("project=" + project);
        config.add("jbs=JDK");

        config.add("[checks]");
        var error = "error=blacklist,author,committer,reviewers,merge,issues,executable,symlink";
        var shouldCheckWhitespace = false;
        var checkWhitespace = old.get("whitespace");
        if (checkWhitespace == null || !checkWhitespace.asString().equals("lax")) {
            error += ",whitespace";
            shouldCheckWhitespace = true;
        }
        var shouldCheckMessage = false;
        var checkMessage = old.get("comments");
        if (checkMessage == null || !checkMessage.asString().equals("lax")) {
            error += ",message,hg-tag";
            shouldCheckMessage = true;
        }
        var checkDuplicateIssues = old.get("bugids");
        if (checkDuplicateIssues == null || !checkDuplicateIssues.equals("dup")) {
            error += ",duplicate-issues";
        }
        config.add(error);

        if (project.startsWith("jdk")) {
            config.add("[repository]");

            var tags = "tags=";
            var checkTags = old.get("tags");
            if (checkTags == null || !checkTags.asString().equals("lax")) {
                var jdkTag = "(?:jdk-(?:[1-9]([0-9]*)(?:\\.(?:0|[1-9][0-9]*)){0,4})(?:\\+(?:(?:[0-9]+))|(?:-ga)))";
                var jdkuTag = "(?:jdk[4-9](?:u\\d{1,3})?-(?:(?:b\\d{2,3})|(?:ga)))";
                var hsTag = "(?:hs\\d\\d(?:\\.\\d{1,2})?-b\\d\\d)";
                tags += jdkTag + "|" + jdkuTag + "|" + hsTag;
            } else {
                tags += ".*";
            }
            config.add(tags);

            var branches = "branches=";
            var checkBranches = old.get("branches");
            if (checkBranches != null && checkBranches.asString().equals("lax")) {
                branches += ".*\n";
            }
            config.add(branches);
        }

        config.add("[census]");
        config.add("version=0");
        config.add("domain=openjdk.org");

        if (shouldCheckWhitespace) {
            config.add("[checks \"whitespace\"]");
            config.add("files=.*\\.cpp|.*\\.hpp|.*\\.c|.*\\.h|.*\\.java");
        }

        config.add("[checks \"merge\"]");
        config.add("message=Merge");

        config.add("[checks \"reviewers\"]");
        if (shouldCheckMessage) {
            config.add("contributors=1");
        } else {
            config.add("contributors=0");
        }
        config.add("ignore=duke");

        config.add("[checks \"committer\"]");
        config.add("role=contributor");

        config.add("[checks \"issues\"]");
        config.add("pattern=^([124-8][0-9]{6}): (\\S.*)$");
        if (!shouldCheckMessage) {
            config.add("required = false");
        }

        return INI.parse(config);
    }

    public static JCheckConfiguration parse(List<String> lines) {
        var ini = INI.parse(lines);
        if (ini.sections().size() == 0) {
            // This is an old-style jcheck conf with only a global section -
            // translate to new configuration style before parsing.
            return new JCheckConfiguration(convert(ini));
        }
        return new JCheckConfiguration(ini);
    }

    public static JCheckConfiguration from(ReadOnlyRepository r, Hash h, Path p) throws IOException {
        return parse(r.lines(p, h).orElse(Collections.emptyList()));
    }

    public static JCheckConfiguration from(ReadOnlyRepository r, Hash h) throws IOException {
        return from(r, h, Path.of(".jcheck", "conf"));
    }

    public static JCheckConfiguration from(ReadOnlyRepository r) throws IOException {
        var master = r.resolve("master")
                      .orElseThrow(() -> new IOException("Cannot resolve 'master' branch"));
        return from(r, master, Path.of(".jcheck", "conf"));
    }
}
