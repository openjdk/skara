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
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;

import javax.inject.Inject;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

public class LaunchersTask extends DefaultTask {
    private Property<Path> toDir;
    private Property<String> os;
    private Property<String> cpu;
    private MapProperty<String, String> launchers;
    private ListProperty<String> options;

    @Inject
    public LaunchersTask(ObjectFactory factory) {
        toDir = factory.property(Path.class);
        os = factory.property(String.class);
        cpu = factory.property(String.class);
        launchers = factory.mapProperty(String.class, String.class);
        options = factory.listProperty(String.class);
    }

    @Input
    ListProperty<String> getOptions() {
        return options;
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
    MapProperty<String, String> getLaunchers() {
        return launchers;
    }

    private static void clearDirectory(Path directory) throws IOException {
        Files.walk(directory)
                .map(Path::toFile)
                .sorted(Comparator.reverseOrder())
                .forEach(File::delete);
    }

    @TaskAction
    void generate() throws IOException {
        var dest = toDir.get().resolve(os.get() + "-" + cpu.get());
        if (Files.isDirectory(dest)) {
            clearDirectory(dest);
        }
        Files.createDirectories(dest);
        var optionString = String.join(" ", options.get());
        for (var entry : launchers.get().entrySet()) {
            var filename = entry.getKey();
            var clazz = entry.getValue();

            if (os.get().equals("windows")) {
                var file = dest.resolve(filename + ".bat");
                try (var w = Files.newBufferedWriter(file)) {
                    w.write("@echo off\r\n");
                    w.write("set DIR=%~dp0\r\n");
                    w.write("set JAVA_HOME=%DIR%..\\image\r\n");
                    w.write("\"%JAVA_HOME%\\bin\\java.exe\" " + optionString + " --module " + clazz + " %*\r\n");
                }
            } else {
                var file = dest.resolve(filename);
                try (var w = Files.newBufferedWriter(file)) {
                    w.write("#!/bin/sh\n");
                    w.write("DIR=$(dirname \"$0\")\n");
                    w.write("export JAVA_HOME=\"${DIR}/../image\"\n");
                    w.write("\"${JAVA_HOME}/bin/java\" " + optionString + " --module " + clazz + " \"$@\"\n");
                }
                if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    var permissions = PosixFilePermissions.fromString("rwxr-xr-x");
                    Files.setPosixFilePermissions(file, permissions);
                }
            }
        }
    }
}
