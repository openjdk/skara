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

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.jvm.tasks.Jar;

import javax.inject.Inject;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class LinkTask extends DefaultTask {
    private Path toDir;
    private String os;
    private String url;
    private MapProperty<String, String> launchers;
    private ListProperty<String> modules;

    @Inject
    public LinkTask(ObjectFactory factory) {
        this.launchers = factory.mapProperty(String.class, String.class);
        this.modules = factory.listProperty(String.class);
    }

    void setToDir(Path toDir) {
        this.toDir = toDir;
    }

    void setOS(String os) {
        this.os = os;
    }

    void setUrl(String url) {
        this.url = url;
    }


    @Input
    MapProperty<String, String> getLaunchers() {
        return launchers;
    }

    @Input
    ListProperty<String> getModules() {
        return modules;
    }

    private static void clearDirectory(Path directory) {
        try {
            Files.walk(directory)
                    .map(Path::toFile)
                    .sorted(Comparator.reverseOrder())
                    .forEach(File::delete);
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
    }

    @TaskAction
    void link() throws IOException {
        var project = getProject().getRootProject();

        var jars = new ArrayList<String>();
        for (var subProject : project.getSubprojects()) {
            for (var task : subProject.getTasksByName("jar", false)) {
                if (task instanceof Jar) {
                    var jarTask = (Jar) task;
                    jars.add(jarTask.getArchiveFile().get().getAsFile().toString());
                }
            }

            try {
                jars.addAll(subProject.getConfigurations()
                                      .getByName("runtimeClasspath")
                                      .getFiles()
                                      .stream()
                                      .map(File::toString)
                                      .collect(Collectors.toList()));
            } catch (UnknownConfigurationException ignored) {}
        }

        var filename = Path.of(URI.create(url).getPath()).getFileName().toString();
        var dirname = filename.replace(".zip", "").replace(".tar.gz", "");
        var jdk = project.getRootDir().toPath().toAbsolutePath().resolve(".jdk").resolve(dirname);
        var dirs = Files.walk(jdk)
                        .filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().equals("jmods"))
                        .collect(Collectors.toList());
        if (dirs.size() != 1) {
            var plural = dirs.size() == 0 ? "no" : "multiple";
            throw new GradleException("JDK at " + jdk.toString() + " contains " + plural + " 'jmods' directories");
        }
        var jmodsDir = dirs.get(0).toAbsolutePath();

        var modulePath = new ArrayList<String>();
        modulePath.add(jmodsDir.toString());
        modulePath.addAll(jars);

        var uniqueModules = new HashSet<String>();
        for (var entry : launchers.get().values()) {
            var firstSlash = entry.indexOf('/');
            uniqueModules.add(entry.substring(0, firstSlash));
        }
        uniqueModules.addAll(modules.get());
        var allModules = new ArrayList<String>(uniqueModules);

        Files.createDirectories(toDir);
        var dest = toDir.resolve(os);
        if (Files.exists(dest) && Files.isDirectory(dest)) {
            clearDirectory(dest);
        }

        Collections.sort(modulePath);
        Collections.sort(allModules);

        var jlink = Path.of(System.getProperty("java.home"), "bin", "jlink").toAbsolutePath().toString();
        project.exec((spec) -> {
            spec.setCommandLine(jlink, "--module-path", String.join(File.pathSeparator, modulePath),
                                       "--add-modules", String.join(",", allModules),
                                       "--no-man-pages",
                                       "--no-header-files",
                                       "--compress", "2",
                                       "--vm", "server",
                                       "--output", dest.toString());
        });

        var currentOS = System.getProperty("os.name").toLowerCase().substring(0, 3);
        if (currentOS.equals(os.substring(0, 3))) {
            var ext = currentOS.startsWith("win") ? ".exe" : "";
            var javaLaunchers = Files.walk(dest)
                                     .filter(Files::isExecutable)
                                     .filter(p -> p.getFileName().toString().equals("java" + ext))
                                     .collect(Collectors.toList());
            if (javaLaunchers.size() != 1) {
                throw new GradleException("Multiple or no java launchers generated for " + os + " image");
            }
            var java = javaLaunchers.get(0);
            project.exec((spec) -> {
                spec.setCommandLine(java, "-Xshare:dump", "-version");
            });
        }
    }
}
