/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.proxy.HttpProxy;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GitProxy {
    public static void main(String[] args) throws IOException, InterruptedException {
        String proxyArgument = null;
        for (var i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.equals("-c") && i < (args.length - 1)) {
                var next = args[i + 1];
                if (next.startsWith("http.proxy")) {
                    var parts = next.split("=");
                    if (parts.length == 2) {
                        proxyArgument = parts[1];
                        break;
                    }
                }
            }
        }

        HttpProxy.setup(proxyArgument);

        var httpsProxyHost = System.getProperty("https.proxyHost");
        var httpProxyHost = System.getProperty("http.proxyHost");

        if (httpsProxyHost == null && httpProxyHost == null) {
            System.err.println("error: no proxy host specified");
            System.err.println("");
            System.err.println("Either set the git config variable 'http.proxy' or");
            System.err.println("set the environment variables HTTP_PROXY and/or HTTPS_PROXY");
            System.exit(1);
        }

        var httpsProxyPort = System.getProperty("https.proxyPort");
        var httpProxyPort = System.getProperty("http.proxyPort");
        var proxyHost = httpsProxyHost != null ? httpsProxyHost : httpProxyHost;
        var proxyPort = httpsProxyPort != null ? httpsProxyPort : httpProxyPort;
        var proxy = proxyHost + ":" + proxyPort;

        System.out.println("info: using proxy " + proxy);

        var gitArgs = new ArrayList<String>();
        gitArgs.add("git");
        gitArgs.addAll(Arrays.asList(args));
        var pb = new ProcessBuilder(gitArgs);
        var env = pb.environment();

        if (httpProxyHost != null) {
            env.put("HTTP_PROXY", proxy);
            env.put("http_proxy", proxy);
        }
        if (httpsProxyHost != null) {
            env.put("HTTPS_PROXY", proxy);
            env.put("https_proxy", proxy);
        }

        var os = System.getProperty("os.name").toLowerCase();
        if (os.startsWith("win")) {
            for (var dir : System.getenv("PATH").split(";")) {
                if (dir.endsWith("Git\\cmd") || dir.endsWith("Git\\bin")) {
                    var gitDir = Path.of(dir).getParent();
                    var connect = gitDir.resolve("mingw64").resolve("bin").resolve("connect.exe");
                    if (Files.exists(connect)) {
                        env.put("GIT_SSH_COMMAND", "ssh -o ProxyCommand='" + connect.toAbsolutePath() +
                                                   " -H " + proxy + " %h %p'");
                        break;
                    }
                }
            }
        } else if (os.startsWith("mac")) {
            // Need to use the BSD netcat since homebrew might install GNU netcat
            env.put("GIT_SSH_COMMAND", "ssh -o ProxyCommand='/usr/bin/nc -X connect -x " + proxy + " %h %p'");
        } else {
            // Assume GNU/Linux and GNU netcat
            env.put("GIT_SSH_COMMAND", "ssh -o ProxyCommand='nc --proxy " + proxy + " %h %p'");
        }
        pb.inheritIO();
        System.exit(pb.start().waitFor());
    }
}
