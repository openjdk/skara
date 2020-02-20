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

import org.openjdk.skara.ssh.SSHConfig;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;

public class Remote {
    public static URI toWebURI(String remotePath) throws IOException {
        if (remotePath.startsWith("git+")) {
            remotePath = remotePath.substring("git+".length());
        }
        if (remotePath.endsWith(".git")) {
            remotePath = remotePath.substring(0, remotePath.length() - ".git".length());
        }
        if (remotePath.startsWith("http")) {
            return URI.create(remotePath);
        } else {
            if (remotePath.startsWith("ssh://")) {
                remotePath = remotePath.substring("ssh://".length()).replaceFirst("/", ":");
            }
            var indexOfColon = remotePath.indexOf(':');
            var indexOfSlash = remotePath.indexOf('/');
            if (indexOfColon != -1) {
                if (indexOfSlash == -1 || indexOfColon < indexOfSlash) {
                    var path = remotePath.contains("@") ? remotePath.split("@")[1] : remotePath;
                    var name = path.split(":")[0];

                    // Could be a Host in the ~/.ssh/config file
                    var sshConfig = Path.of(System.getProperty("user.home"), ".ssh", "config");
                    if (Files.exists(sshConfig)) {
                        for (var host : SSHConfig.parse(sshConfig).hosts()) {
                            if (host.name().equals(name)) {
                                var hostName = host.hostName();
                                if (hostName != null) {
                                    return URI.create("https://" + hostName + "/" + path.split(":")[1]);
                                }
                            }
                        }
                    }

                    // Otherwise is must be a domain
                    return URI.create("https://" + path.replace(":", "/"));
                }
            }
        }

        throw new IOException("error: cannot find remote repository for " + remotePath);
    }

    public static URI toURI(String remotePath) throws IOException {
        if (remotePath.startsWith("git+")) {
            remotePath = remotePath.substring("git+".length());
        }
        if (remotePath.startsWith("http://") ||
            remotePath.startsWith("https://") ||
            remotePath.startsWith("ssh://") ||
            remotePath.startsWith("file://") ||
            remotePath.startsWith("git://")) {
            return URI.create(remotePath);
        }

        var indexOfColon = remotePath.indexOf(':');
        var indexOfSlash = remotePath.indexOf('/');
        if (indexOfColon != -1) {
            if (indexOfSlash == -1 || indexOfColon < indexOfSlash) {
                var path = remotePath.contains("@") ? remotePath.split("@")[1] : remotePath;
                var name = path.split(":")[0];

                // Could be a Host in the ~/.ssh/config file
                var sshConfig = Path.of(System.getProperty("user.home"), ".ssh", "config");
                if (Files.exists(sshConfig)) {
                    for (var host : SSHConfig.parse(sshConfig).hosts()) {
                        if (host.name().equals(name)) {
                            var hostName = host.hostName();
                            if (hostName != null) {
                                var username = host.user();
                                if (username == null) {
                                    username = remotePath.contains("@") ? remotePath.split("@")[0] : null;
                                }
                                var userPrefix = username == null ? "" : username + "@";
                                return URI.create("ssh://" + userPrefix + hostName + "/" + path.split(":")[1]);
                            }
                        }
                    }
                }

                // Otherwise is must be a domain
                var userPrefix = remotePath.contains("@") ? remotePath.split("@")[0] + "@" : "";
                return URI.create("ssh://" + userPrefix + path.replace(":", "/"));
            }
        }

        throw new IOException("error: cannot construct proper URI for " + remotePath);
    }
}
