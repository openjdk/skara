/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.gradle.reproduce;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

public class ReproducePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.afterEvaluate((p) -> {
            for (var entry : project.getAllTasks(true).entrySet()) {
                for (var task : entry.getValue()) {
                    if (task instanceof AbstractArchiveTask archiveTask) {
                        archiveTask.setPreserveFileTimestamps(false);
                        archiveTask.setReproducibleFileOrder(true);
                    }
                }
            }
        });

        project.getTasks().create("reproduce", ReproduceTask.class, (task) -> {
            task.setDockerfile("Dockerfile");
        });
    }
}
