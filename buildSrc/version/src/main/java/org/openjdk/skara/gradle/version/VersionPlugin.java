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

package org.openjdk.skara.gradle.version;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.GradleException;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

import static java.util.stream.Collectors.toList;

public class VersionPlugin implements Plugin<Project> {
    public void apply(Project project) {
        var pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

        try {
            var p = pb.start();
            var bytes = p.getInputStream().readAllBytes();
            var status = p.waitFor();
            if (status == 0) {
                var desc = new String(bytes, StandardCharsets.UTF_8);
                if (desc.endsWith("\n")) {
                    desc = desc.substring(0, desc.length() - 1);
                }
                project.setProperty("version", desc);
            } else {
                var root = project.getRootProject().getRootDir().toPath();
                var versionTxt = root.resolve("version.txt");
                if (Files.exists(versionTxt)) {
                    var lines = Files.lines(versionTxt).collect(toList());
                    if (!lines.isEmpty()) {
                        project.setProperty("version", lines.get(0));
                    } else {
                        project.setProperty("version", "unknown");
                    }
                } else {
                    project.setProperty("version", "unknown");
                }
            }
        } catch (InterruptedException e) {
            throw new GradleException("'git rev-parse' was interrupted", e);
        } catch (IOException e) {
            throw new GradleException("could not read output from 'git rev-parse'", e);
        }
    }
}
