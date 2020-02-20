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
package org.openjdk.skara.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class GitCredentials {
    private final String host;
    private final String path;
    private final String username;
    private final String password;
    private final String protocol;

    public GitCredentials(String host, String path, String username, String password, String protocol) {
        this.host = host;
        this.path = path;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
    }

    public String host() {
        return host;
    }

    public String path() {
        return path;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String protocol() {
        return protocol;
    }

    public static GitCredentials fill(String host, String path, String username, String password, String protocol) throws IOException {
        try {
            var pb = new ProcessBuilder("git", "credential", "fill");
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            var p = pb.start();

            var gitStdin = p.getOutputStream();
            String input = "host=" + host + "\n";
            if (path != null) {
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
                input += "path=" + path + "\n";
            }
            if (username != null) {
                input += "username=" + username + "\n";
            }
            if (password != null) {
                input += "password=" + password + "\n";
            }
            if (protocol != null) {
                input += "protocol=" + protocol + "\n";
            }
            gitStdin.write((input + "\n").getBytes(StandardCharsets.UTF_8));
            gitStdin.flush();

            var bytes = p.getInputStream().readAllBytes();
            var exited = p.waitFor(10, TimeUnit.MINUTES);
            var exitValue = p.exitValue();
            if (!exited || exitValue != 0) {
                throw new IOException("'git credential' exited with value: " + exitValue);
            }

            protocol = null;
            username = null;
            password = null;
            path = null;
            host = null;
            for (var line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("host=")) {
                    host = line.split("=")[1];
                } else if (line.startsWith("username=")) {
                    username = line.split("=")[1];
                } else if (line.startsWith("password=")) {
                    password = line.split("=")[1];
                } else if (line.startsWith("protocol=")) {
                    protocol = line.split("=")[1];
                } else if (line.startsWith("path=")) {
                    String[] parts = line.split("=");
                    path = parts.length > 1 ? parts[1] : null; // value can be empty
                } else {
                    throw new IOException("'git credential' returned unexpected line: " + line);
                }
            }

            return new GitCredentials(host, path, username, password, protocol);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public static void approve(GitCredentials credentials) throws IOException {
        try {
            var pb = new ProcessBuilder("git", "credential", "approve");
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            var p = pb.start();

            var gitStdin = p.getOutputStream();
            String input = "host=" + credentials.host() + "\n" +
                           "path=" + credentials.path() + "\n" +
                           "username=" + credentials.username() + "\n" +
                           "password=" + credentials.password() + "\n" +
                           "protocol=" + credentials.protocol() + "\n";
            gitStdin.write((input + "\n").getBytes(StandardCharsets.UTF_8));
            gitStdin.flush();
            var res = p.waitFor();
            if (res != 0) {
                throw new IOException("'git credential approve' exited with value: " + res);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public static void reject(GitCredentials credentials) throws IOException {
        try {
            var pb = new ProcessBuilder("git", "credential", "reject");
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            var p = pb.start();

            var gitStdin = p.getOutputStream();
            String input = "host=" + credentials.host() + "\n" +
                           "path=" + credentials.path() + "\n" +
                           "username=" + credentials.username() + "\n" +
                           "password=" + credentials.password() + "\n" +
                           "protocol=" + credentials.protocol() + "\n";
            gitStdin.write((input + "\n").getBytes(StandardCharsets.UTF_8));
            gitStdin.flush();
            var res = p.waitFor();
            if (res != 0) {
                throw new IOException("'git credential reject' exited with value: " + res);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }
}
