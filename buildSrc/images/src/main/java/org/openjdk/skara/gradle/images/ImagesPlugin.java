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
import java.util.HashSet;
import java.util.Map;
import java.io.File;
import java.nio.file.Path;

public class ImagesPlugin implements Plugin<Project> {
    private static String getOS() {
        var p = System.getProperty("os.name").toLowerCase();
        if (p.startsWith("win")) {
            return "windows";
        }
        if (p.startsWith("mac")) {
            return "macos";
        }
        if (p.startsWith("linux")) {
            return "linux";
        }
        if (p.startsWith("sunos")) {
            return "solaris";
        }

        throw new RuntimeException("Unknown operating system: " + System.getProperty("os.name"));
    }

    private static String getCPU() {
        var p = System.getProperty("os.arch").toLowerCase();
        if (p.startsWith("amd64") || p.startsWith("x86_64") || p.startsWith("x64")) {
            return "x64";
        }
        if (p.startsWith("x86") || p.startsWith("i386")) {
            return "x86";
        }
        if (p.startsWith("sparc")) {
            return "sparc";
        }
        if (p.startsWith("ppc")) {
            return "ppc";
        }
        if (p.startsWith("arm")) {
            return "arm";
        }
        if (p.startsWith("aarch64")) {
            return "aarch64";
        }

        throw new RuntimeException("Unknown CPU: " + System.getProperty("os.arch"));
    }

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
        var rootProject = project.getRootProject();
        var buildDir = project.getBuildDir().toPath().toAbsolutePath();

        imageEnvironmentContainer.all(new Action<ImageEnvironment>() {
            public void execute(ImageEnvironment env) {
                var parts = env.getName().split("_");;
                var isLocal = parts.length == 1 && parts[0].equals("local");
                var os = isLocal ? getOS() : parts[0];
                var cpu = isLocal ? getCPU() : parts[1];
                var osAndCpuPascalCased =
                    os.substring(0, 1).toUpperCase() + os.substring(1) +
                    cpu.substring(0, 1).toUpperCase() + cpu.substring(1);
                var subName = isLocal ? "Local" : osAndCpuPascalCased;

                var downloadTaskName = "download" + subName + "JDK";
                if (!isLocal && rootProject.getTasksByName(downloadTaskName, false).isEmpty()) {
                    project.getRootProject().getTasks().register(downloadTaskName, DownloadJDKTask.class, (task) -> {
                        task.getUrl().set(env.getUrl());
                        task.getSha256().set(env.getSha256());
                        task.getToDir().set(rootDir.resolve(".jdk"));
                    });
                }

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

                    if (!isLocal) {
                        task.dependsOn(project.getRootProject().getTasksByName(downloadTaskName, false));
                        task.getUrl().set(env.getUrl());
                    } else {
                        task.getUrl().set("local");
                    }
                    task.getToDir().set(buildDir.resolve("images"));
                    task.getOS().set(os);
                    task.getCPU().set(cpu);
                    task.getLaunchers().set(env.getLaunchers());
                    task.getModules().set(env.getModules());
                    if (isLocal) {
                        task.getJLink().set(Path.of(System.getProperty("java.home"), "bin", "jlink").toAbsolutePath().toString());
                    } else {
                        var javaHomes = Map.of(
                            "linux_x64", ".jdk/openjdk-13.0.1_linux-x64_bin/jdk-13.0.1",
                            "macos_x64", ".jdk/openjdk-13.0.1_osx-x64_bin/jdk-13.0.1.jdk/Contents/Home",
                            "windows_x64", ".jdk\\openjdk-13.0.1_windows-x64_bin"
                        );
                        var currentOS = getOS();
                        var currentCPU = getCPU();
                        var javaHome = javaHomes.get(currentOS + "_" + currentCPU);
                        if (javaHome == null) {
                            throw new RuntimeException("No JDK found for " + currentOS + " " + currentCPU);
                        }
                        if (currentOS.equals("windows")) {
                            task.getJLink().set(rootDir.toString() + "\\" + javaHome + "\\bin\\jlink.exe");
                        } else {
                            task.getJLink().set(rootDir.toString() + "/" + javaHome + "/bin/jlink");
                        }
                    }
                });

                var launchersTaskName = "launchers" + subName;
                project.getTasks().register(launchersTaskName, LaunchersTask.class, (task) -> {
                    task.getLaunchers().set(env.getLaunchers());
                    task.getOptions().set(env.getOptions());
                    task.getToDir().set(buildDir.resolve("launchers"));
                    task.getOS().set(os);
                    task.getCPU().set(cpu);
                });

                var zipTaskName = "bundleZip" + subName;
                project.getTasks().register(zipTaskName, Zip.class, (task) -> {
                    task.dependsOn(projectPath + ":" + linkTaskName);
                    task.dependsOn(projectPath + ":" + launchersTaskName);

                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                    task.getArchiveBaseName().set(project.getName());
                    task.getArchiveClassifier().set(os + "-" + cpu);
                    task.getArchiveExtension().set("zip");

                    if (env.getMan().isPresent()) {
                        var root = project.getRootProject().getRootDir().toPath().toAbsolutePath();
                        task.from(root.resolve(env.getMan().get()).toString(), (s) -> {
                            s.into("bin/man");
                        });
                    }

                    var subdir = os + "-" + cpu;
                    task.from(buildDir.resolve("images").resolve(subdir), (s) -> {
                        s.into("image");
                    });
                    task.from(buildDir.resolve("launchers").resolve(subdir), (s) -> {
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
                    task.getArchiveClassifier().set(os + "-" + cpu);
                    task.getArchiveExtension().set("tar.gz");
                    task.setCompression(Compression.GZIP);

                    if (env.getMan().isPresent()) {
                        var root = project.getRootProject().getRootDir().toPath().toAbsolutePath();
                        task.from(root.resolve(env.getMan().get()).toString(), (s) -> {
                            s.into("bin/man");
                        });
                    }

                    var subdir = os + "-" + cpu;
                    task.from(buildDir.resolve("images").resolve(subdir), (s) -> {
                        s.into("image");
                    });
                    task.from(buildDir.resolve("launchers").resolve(subdir), (s) -> {
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

                if (!isLocal) {
                    taskNames.add(imageTaskName);
                }
            }
        });

        project.getTasks().register("images", DefaultTask.class, (task) -> {
            for (var name : taskNames) {
                task.dependsOn(projectPath + ":" + name);
            }
        });
    }
}
