/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.proxy;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class HttpProxy {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.proxy");

    private static boolean setProxyHostAndPortBasedOn(String protocol, URI uri) {
        var port = String.valueOf(uri.getPort() == -1 ? 80 : uri.getPort());
        if (System.getProperty(protocol + ".proxyHost") == null) {
            log.fine("Setting " + protocol + " proxy to " + uri.getHost() + ":" + port);
            System.setProperty(protocol + ".proxyHost", uri.getHost());
            System.setProperty(protocol + ".proxyPort", port);
            return true;
        }

        log.fine("Not overriding " + protocol + " proxy setting. Current value: " +
                         System.getProperty(protocol + ".proxyHost") + ":" + System.getProperty(protocol + ".proxyPort"));
        return false;
    }

    public static void setup() {
        setup(null);
    }

    public static void setup(String argument) {
        if (argument != null) {
            if (!argument.startsWith("http://") &&
                !argument.startsWith("https://")) {
                // Try to parse it as a http url - we only care about the host and port
                argument = "http://" + argument;
            }

            try {
                var uri = new URI(argument);
                for (var protocol : List.of("http", "https")) {
                    setProxyHostAndPortBasedOn(protocol, uri);
                }
                return;
            } catch (URISyntaxException e) {
                // pass
            }
        }

        try {
            var pb = new ProcessBuilder("git", "config", "http.proxy");
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();

            var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            var res = p.waitFor();
            if (res == 0 && !output.isEmpty()) {
                if (!output.startsWith("http://") && !output.startsWith("https://")) {
                    // Try to parse it as a http url - we only care about the host and port
                    output = "http://" + output;
                }
                var uri = new URI(output);
                for (var protocol : List.of("http", "https")) {
                    setProxyHostAndPortBasedOn(protocol, uri);
                }
                return;
            }
        } catch (InterruptedException | IOException | URISyntaxException e) {
            // pass
        }

        boolean hasSetProxy = false;
        for (var key : List.of("http_proxy", "https_proxy")) {
            var value = System.getenv(key);
            value = value == null ? System.getenv(key.toUpperCase()) : value;
            if (value != null) {
                var protocol = key.split("_")[0].toLowerCase();
                try {
                    if (!value.startsWith("http://") && !value.startsWith("https://")) {
                        // Try to parse it as a http url - we only care about the host and port
                        value = "http://" + value;
                    }
                    var uri = new URI(value);
                    hasSetProxy |= setProxyHostAndPortBasedOn(protocol, uri);
                } catch (URISyntaxException e) {
                    // pass
                }
            }
        }
        var no_proxy = System.getenv("no_proxy");
        no_proxy = no_proxy == null ? System.getenv("NO_PROXY") : no_proxy;
        if (no_proxy != null) {
            if (System.getProperty("http.nonProxyHosts") == null || hasSetProxy) {
                var hosts = Arrays.stream(no_proxy.split(","))
                                  .map(s -> s.startsWith(".") ? "*" + s : s)
                                  .collect(Collectors.toList());
                System.setProperty("http.nonProxyHosts", String.join("|", hosts));
                log.fine("Setting nonProxyHosts to " + String.join("|", hosts));
            } else {
                log.fine("Not overriding nonProxyHosts setting. Current value: " +
                                 System.getProperty("http.nonProxyHosts"));
            }
        }
    }
}
