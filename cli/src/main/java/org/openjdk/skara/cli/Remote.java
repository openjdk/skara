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
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

public class Remote {
    private static URI sshCanonicalize(URI uri) throws IOException {
        var arg = uri.getUserInfo() == null ? uri.getHost() : uri.getUserInfo() + "@" + uri.getHost();
        var pb = new ProcessBuilder("ssh", "-G", arg);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        var p = pb.start();

        var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            var res = p.waitFor();
            if (res != 0) {
                throw new IOException("ssh -G " + arg + " exited with non-zero exit code: " + res);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        String hostname = null;
        String username = null;
        for (var line : output.split("\n")) {
            var parts = line.trim().split(" ");
            if (parts.length == 2) {
                var key = parts[0];
                var value = parts[1];
                if (key.equals("hostname")) {
                    hostname = value;
                } else if (key.equals("user")) {
                    username = value;
                }
            }
        }

        if (hostname == null) {
            throw new IOException("ssh -G " + arg + " did not output a hostname");
        }

        return username == null ?
            URI.create("ssh://" + hostname + uri.getPath()) :
            URI.create("ssh://" + username + "@" + hostname + uri.getPath());
    }

    public static URI toWebURI(String remotePath) throws IOException {
        var uri = toURI(remotePath);
        if (uri.getScheme().equals("file://")) {
            throw new IOException("Cannot create web URI for file path: " + uri.toString());
        }

        // Use https://, drop eventual .git from path and drop authority
        var path = uri.getPath();
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        return URI.create("https://" + uri.getHost() + path);
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
                var uri = URI.create("ssh://" + remotePath.replace(":", "/"));
                return sshCanonicalize(uri);
            }
        }

        throw new IOException("Cannot construct URI for " + remotePath);
    }
}
