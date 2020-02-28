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
import org.gradle.api.file.*;

import javax.inject.Inject;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class LinkTask extends DefaultTask {
    private final Property<String> os;
    private final Property<String> cpu;
    private final Property<String> url;
    private final Property<String> jlink;
    private final Property<Path> toDir;
    private final MapProperty<String, String> launchers;
    private final ListProperty<String> modules;
    private final SetProperty<RegularFile> modulePath;
    private final SetProperty<FileSystemLocation> runtimeModules;

    @Inject
    public LinkTask(ObjectFactory factory) {
        os = factory.property(String.class);
        cpu = factory.property(String.class);
        url = factory.property(String.class);
        jlink = factory.property(String.class);
        toDir = factory.property(Path.class);
        launchers = factory.mapProperty(String.class, String.class);
        modules = factory.listProperty(String.class);
        modulePath = factory.setProperty(RegularFile.class);
        runtimeModules = factory.setProperty(FileSystemLocation.class);
    }

    @OutputDirectory
    Property<Path> getToDir() {
        return toDir;
    }

    @Input
    Property<String> getOS() {
        return os;
    }

    @Input
    Property<String> getCPU() {
        return cpu;
    }

    @Input
    Property<String> getUrl() {
        return url;
    }

    @Input
    Property<String> getJLink() {
        return jlink;
    }

    @Input
    MapProperty<String, String> getLaunchers() {
        return launchers;
    }

    @Input
    ListProperty<String> getModules() {
        return modules;
    }

    @InputFiles
    SetProperty<RegularFile> getModulePath() {
        return modulePath;
    }

    @InputFiles
    SetProperty<FileSystemLocation> getRuntimeModules() {
        return runtimeModules;
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

        var modularJars = new ArrayList<String>();
        for (var jar : modulePath.get()) {
            modularJars.add(jar.getAsFile().toString());
        }
        for (var jar : runtimeModules.get()) {
            modularJars.add(jar.getAsFile().toString());
        }

        Path jdk = null;
        if (!url.get().equals("local")) {
            var filename = Path.of(URI.create(url.get()).getPath()).getFileName().toString();
            var dirname = filename.replace(".zip", "").replace(".tar.gz", "");
            jdk = project.getRootDir().toPath().toAbsolutePath().resolve(".jdk").resolve(dirname);
        } else {
            jdk = Path.of(System.getProperty("java.home"));
        }
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
        modulePath.addAll(modularJars);

        var uniqueModules = new HashSet<String>();
        for (var entry : launchers.get().values()) {
            var firstSlash = entry.indexOf('/');
            uniqueModules.add(entry.substring(0, firstSlash));
        }
        uniqueModules.addAll(modules.get());
        var allModules = new ArrayList<String>(uniqueModules);

        Files.createDirectories(toDir.get());
        var dest = toDir.get().resolve(os.get() + "-" + cpu.get());
        if (Files.exists(dest) && Files.isDirectory(dest)) {
            clearDirectory(dest);
        }

        Collections.sort(modulePath);
        Collections.sort(allModules);

        project.exec((spec) -> {
            spec.setCommandLine(jlink.get(), "--module-path", String.join(File.pathSeparator, modulePath),
                                       "--add-modules", String.join(",", allModules),
                                       "--no-man-pages",
                                       "--no-header-files",
                                       "--compress", "2",
                                       "--vm", "server",
                                       "--output", dest.toString());
        });

        var currentOS = System.getProperty("os.name").toLowerCase().substring(0, 3);
        if (os.get().equals("local") || currentOS.equals(os.get().substring(0, 3))) {
            var ext = currentOS.startsWith("win") ? ".exe" : "";
            var javaLaunchers = Files.walk(dest)
                                     .filter(Files::isExecutable)
                                     .filter(p -> p.getFileName().toString().equals("java" + ext))
                                     .collect(Collectors.toList());
            if (javaLaunchers.size() != 1) {
                throw new GradleException("Multiple or no java launchers generated for " + os.get() + "-" + cpu.get() + " image");
            }
            var java = javaLaunchers.get(0);
            project.exec((spec) -> {
                spec.setCommandLine(java, "-Xshare:dump", "-version");
            });
        }
    }
}
