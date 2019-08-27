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

package org.openjdk.skara.ssh;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class SSHConfig {
    public static class Host {
        private final String name;
        private final String user;
        private final String hostName;
        private final int port;
        private final List<String> preferredAuthentications;
        private final Path identifyFile;
        private final String proxyCommand;
        private final boolean forwardAgent;
        private final boolean tcpKeepAlive;
        private final boolean identitiesOnly;

        Host(String name, Map<String, String> fields) {
            this.name = name;
            user = fields.get("User");
            hostName = fields.get("Hostname");
            port = Integer.parseInt(fields.getOrDefault("Port", "22"));

            if (fields.containsKey("PreferredAuthentications")) {
                preferredAuthentications = Arrays.asList(fields.get("PreferredAuthentications").split(","));
            } else {
                preferredAuthentications = List.of();
            }

            if (fields.containsKey("IdentityFile")) {
                identifyFile = Path.of(fields.get("IdentityFile"));
            } else {
                identifyFile = null;
            }

            proxyCommand = fields.get("proxyCommand");
            forwardAgent = Objects.equals(fields.get("ForwardAgent"), "yes");
            tcpKeepAlive = Objects.equals(fields.get("TCPKeepAlive"), "yes");
            identitiesOnly = Objects.equals(fields.get("IdentitiesOnly"), "yes");
        }

        public String name() {
            return name;
        }

        public String user() {
            return user;
        }

        public String hostName() {
            return hostName;
        }

        public int port() {
            return port;
        }

        public List<String> preferredAuthentications() {
            return preferredAuthentications;
        }

        public Path identityFile() {
            return identifyFile;
        }

        public String proxyCommand() {
            return proxyCommand;
        }

        public boolean forwardAgent() {
            return forwardAgent;
        }

        public boolean tcpKeepAlive() {
            return tcpKeepAlive;
        }

        public boolean identitiesOnly() {
            return identitiesOnly;
        }
    }

    private final List<Host> hosts;

    public SSHConfig(List<Host> hosts) {
        this.hosts = hosts;
    }

    public List<Host> hosts() {
        return hosts;
    }

    public static SSHConfig parse(Path p) throws IOException  {
        return parse(Files.readAllLines(p));
    }

    public static SSHConfig parse(List<String> lines) {
        var hosts = new ArrayList<Host>();
        var i = 0;
        while (i < lines.size()) {
            var line = lines.get(i);
            if (line.startsWith("Host")) {
                var name = line.split(" ")[1];
                i++;

                var fields = new HashMap<String, String>();
                while (i < lines.size() && !lines.get(i).startsWith("Host")) {
                    var field = lines.get(i);
                    i++;
                    if (!field.isEmpty()) {
                        var nameAndValue = field.trim().split(" ");
                        fields.put(nameAndValue[0], nameAndValue[1]);
                    }
                }

                hosts.add(new Host(name, fields));
            }
        }

        return new SSHConfig(hosts);
    }
}
