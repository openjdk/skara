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

package org.openjdk.skara.gradle.images;

import org.gradle.api.*;
import org.gradle.api.file.Directory;
import org.gradle.api.tasks.bundling.*;
import org.gradle.api.artifacts.UnknownConfigurationException;

import java.util.ArrayList;
import java.io.File;

public class ImagesPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        NamedDomainObjectContainer<ImageEnvironment> imageEnvironmentContainer =
            project.container(ImageEnvironment.class, new NamedDomainObjectFactory<ImageEnvironment>() {
                public ImageEnvironment create(String name) {
                    return new ImageEnvironment(name, project.getObjects());
                }
            });
        project.getExtensions().add("images", imageEnvironmentContainer);

        var projectPath = project.getPath();
        var taskNames = new ArrayList<String>();
        var rootDir = project.getRootDir().toPath().toAbsolutePath();
        var buildDir = project.getBuildDir().toPath().toAbsolutePath();

        imageEnvironmentContainer.all(new Action<ImageEnvironment>() {
            public void execute(ImageEnvironment env) {
                var name = env.getName();
                var subName = name.substring(0, 1).toUpperCase() + name.substring(1);

                var downloadTaskName = "download" + subName + "JDK";
                project.getTasks().register(downloadTaskName, DownloadJDKTask.class, (task) -> {
                    task.getUrl().set(env.getUrl());
                    task.getSha256().set(env.getSha256());
                    task.getToDir().set(rootDir.resolve(".jdk"));
                });

                var linkTaskName = "link" + subName;
                project.getTasks().register(linkTaskName, LinkTask.class, (task) -> {
                    for (var jarTask : project.getTasksByName("jar", true)) {
                        if (jarTask instanceof Jar) {
                            task.getModulePath().add(((Jar) jarTask).getArchiveFile());
                        }
                    }

                    try {
                        var runtimeClasspath = project.getConfigurations().getByName("runtimeClasspath");
                        task.getRuntimeModules().addAll(runtimeClasspath.getElements());
                        task.dependsOn(runtimeClasspath);
                    } catch (UnknownConfigurationException e) {
                        // ignored
                    }

                    task.dependsOn(projectPath + ":" + downloadTaskName);
                    task.getToDir().set(buildDir.resolve("images"));
                    task.getUrl().set(env.getUrl());
                    task.getOS().set(name);
                    task.getLaunchers().set(env.getLaunchers());
                    task.getModules().set(env.getModules());
                });

                var launchersTaskName = "launchers" + subName;
                project.getTasks().register(launchersTaskName, LaunchersTask.class, (task) -> {
                    task.getLaunchers().set(env.getLaunchers());
                    task.getOptions().set(env.getOptions());
                    task.getToDir().set(buildDir.resolve("launchers"));
                    task.getOS().set(name);
                });

                var zipTaskName = "bundleZip" + subName;
                project.getTasks().register(zipTaskName, Zip.class, (task) -> {
                    task.dependsOn(projectPath + ":" + linkTaskName);
                    task.dependsOn(projectPath + ":" + launchersTaskName);

                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                    task.getArchiveBaseName().set(project.getName());
                    task.getArchiveClassifier().set(name);
                    task.getArchiveExtension().set("zip");

                    if (env.getMan().isPresent()) {
                        var root = project.getRootProject().getRootDir().toPath().toAbsolutePath();
                        task.from(root.resolve(env.getMan().get()).toString(), (s) -> {
                            s.into("bin/man");
                        });
                    }

                    task.from(buildDir.resolve("images").resolve(name), (s) -> {
                        s.into("image");
                    });
                    task.from(buildDir.resolve("launchers").resolve(name), (s) -> {
                        s.into("bin");
                    });
                });

                var gzipTaskName = "bundleTarGz" + subName;
                project.getTasks().register(gzipTaskName, Tar.class, (task) -> {
                    task.dependsOn(projectPath + ":" + linkTaskName);
                    task.dependsOn(projectPath + ":" + launchersTaskName);

                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                    task.getArchiveBaseName().set(project.getName());
                    task.getArchiveClassifier().set(name);
                    task.getArchiveExtension().set("tar.gz");
                    task.setCompression(Compression.GZIP);

                    if (env.getMan().isPresent()) {
                        var root = project.getRootProject().getRootDir().toPath().toAbsolutePath();
                        task.from(root.resolve(env.getMan().get()).toString(), (s) -> {
                            s.into("bin/man");
                        });
                    }

                    task.from(buildDir.resolve("images").resolve(name), (s) -> {
                        s.into("image");
                    });
                    task.from(buildDir.resolve("launchers").resolve(name), (s) -> {
                        s.into("bin");
                    });
                });

                var imageTaskName = "image" + subName;
                project.getTasks().register(imageTaskName, DefaultTask.class, (task) -> {
                    for (var bundle : env.getBundles().get()) {
                        if (bundle.equals("zip")) {
                            task.dependsOn(projectPath + ":" + zipTaskName);
                        } else if (bundle.equals("tar.gz")) {
                            task.dependsOn(projectPath + ":" + gzipTaskName);
                        }
                    }
                });

                taskNames.add(imageTaskName);
            }
        });

        project.getTasks().register("images", DefaultTask.class, (task) -> {
            for (var name : taskNames) {
                task.dependsOn(projectPath + ":" + name);
            }
        });
    }
}
