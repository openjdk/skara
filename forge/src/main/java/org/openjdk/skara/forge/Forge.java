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
package org.openjdk.skara.forge;

import org.openjdk.skara.host.*;
import org.openjdk.skara.json.JSONObject;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public interface Forge extends Host {
    String name();
    Optional<HostedRepository> repository(String name);
    boolean supportsReviewBody();

    static Forge from(String name, URI uri, Credential credential, JSONObject configuration) {
        var factory = ForgeFactory.getForgeFactories().stream()
                                    .filter(f -> f.name().equals(name))
                                    .findFirst();
        if (factory.isEmpty()) {
            throw new RuntimeException("No forge factory named '" + name + "' found - check module path");
        }
        return factory.get().create(uri, credential, configuration);
    }

    static Optional<Forge> from(URI uri, Credential credential, JSONObject configuration) {
        var factories = ForgeFactory.getForgeFactories();

        var hostname = uri.getHost();
        var knownHostFactories = factories.stream()
                                          .filter(f -> f.knownHosts().contains(hostname))
                                          .collect(Collectors.toList());
        if (knownHostFactories.size() == 1) {
            var factory = knownHostFactories.get(0);
            return Optional.of(factory.create(uri, credential, configuration));
        }

        var sorted = factories.stream()
                              .sorted(Comparator.comparing(f -> !hostname.contains(f.name())))
                              .collect(Collectors.toList());
        for (var factory : sorted) {
            var forge = factory.create(uri, credential, configuration);
            if (forge.isValid()) {
                return Optional.of(forge);
            }
        }
        return Optional.empty();
    }

    static Optional<Forge> from(URI uri, Credential credential) {
        return from(uri, credential, null);
    }

    static Optional<Forge> from(URI uri) {
        return from(uri, null);
    }
}
