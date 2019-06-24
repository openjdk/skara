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

package org.openjdk.skara.gradle.reproduce;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.Action;
import org.gradle.process.ExecSpec;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.nio.file.Path;

class BuildImageAction implements Action<ExecSpec> {
    private final Path rootDir;
    private final String dockerfile;
    private String tag = null;

    BuildImageAction(Path rootDir, String dockerfile) {
        this.rootDir = rootDir;
        this.dockerfile = dockerfile;
    }

    @Override
    public void execute(ExecSpec spec) {
        var javaOptions = new ArrayList<String>();
        var httpProxyHost = System.getProperty("http.proxyHost");
        if (httpProxyHost != null) {
            javaOptions.add("-Dhttp.proxyHost=" + httpProxyHost);
        }
        var httpProxyPort = System.getProperty("http.proxyPort");
        if (httpProxyHost != null) {
            javaOptions.add("-Dhttp.proxyPort=" + httpProxyHost);
        }

        var httpsProxyHost = System.getProperty("https.proxyHost");
        if (httpsProxyHost != null) {
            javaOptions.add("-Dhttps.proxyHost=" + httpsProxyHost);
        }
        var httpsProxyPort = System.getProperty("https.proxyPort");
        if (httpsProxyPort != null) {
            javaOptions.add("-Dhttps.proxyPort=" + httpsProxyPort);
        }

        var nonProxyHosts = System.getProperty("http.nonProxyHosts");
        if (nonProxyHosts != null) {
            javaOptions.add("-Dhttp.nonProxyHosts=" + nonProxyHosts);
        }

        tag = UUID.randomUUID().toString();
        var command = new ArrayList<String>();
        command.addAll(List.of("docker", "build"));
        if (!javaOptions.isEmpty()) {
            command.add("--build-arg");
            command.add("JAVA_OPTIONS=" + String.join(" ", javaOptions));
        }
        var httpProxy = System.getenv("http_proxy");
        if (httpProxy == null) {
            httpProxy = System.getenv("HTTP_PROXY");
        }
        if (httpProxy != null) {
            command.add("--build-arg");
            command.add("http_proxy=" + httpProxy);
        }
        var httpsProxy = System.getenv("https_proxy");
        if (httpsProxy == null) {
            httpsProxy = System.getenv("HTTPS_PROXY");
        }
        if (httpsProxy != null) {
            command.add("--build-arg");
            command.add("https_proxy=" + httpsProxy);
        }
        var noProxy = System.getenv("no_proxy");
        if (noProxy == null) {
            noProxy = System.getenv("NO_PROXY");
        }
        if (noProxy != null) {
            command.add("--build-arg");
            command.add("no_proxy=" + noProxy);
        }
        command.addAll(List.of("--tag", tag, "--file", dockerfile));
        command.add(rootDir.toString());

        spec.setCommandLine(command);
    }

    String tag() {
        return tag;
    }
}

class RemoveImageAction implements Action<ExecSpec> {
    private final String tag;

    RemoveImageAction(String tag) {
        this.tag = tag;
    }

    @Override
    public void execute(ExecSpec spec) {
        spec.setCommandLine(List.of("docker", "image", "rm", "--force", tag));
    }
}

public class ReproduceTask extends DefaultTask {
    private String dockerfile;

    @Input
    public String getDockerfile() {
        return dockerfile;
    }
    public void setDockerfile(String dockerfile) {
        this.dockerfile = dockerfile;
    }

    @TaskAction
    void reproduce() {
        var project = getProject().getRootProject();
        var rootDir = project.getRootDir().toPath().toAbsolutePath();

        var reproduce = new BuildImageAction(rootDir, dockerfile);
        project.exec(reproduce);
        project.exec(new RemoveImageAction(reproduce.tag()));
    }
}
