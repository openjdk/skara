/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class HttpProxy {
    public static void setup() {
        for (var key : List.of("http_proxy", "https_proxy")) {
            var value = System.getenv(key);
            value = value == null ? System.getenv(key.toUpperCase()) : value;
            if (value != null) {
                var protocol = key.split("_")[0];
                try {
                    var uri = new URI(value);
                    if (System.getProperty(protocol + ".proxyHost") == null && uri.getHost() != null) {
                        System.setProperty(protocol + ".proxyHost", uri.getHost());
                        System.setProperty(protocol + ".proxyPort", String.valueOf(uri.getPort()));
                    }
                } catch (URISyntaxException e) {
                    // pass
                }
            }
        }
        var no_proxy = System.getenv("no_proxy");
        no_proxy = no_proxy == null ? System.getenv("NO_PROXY") : no_proxy;
        if (no_proxy != null && System.getProperty("http.nonProxyHosts") == null) {
            var hosts = Arrays.stream(no_proxy.split(","))
                              .map(s -> s.startsWith(".") ? "*" + s : s)
                              .collect(Collectors.toList());
            System.setProperty("http.nonProxyHosts", String.join("|", hosts));
        }
    }
}
