/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.time.Duration;
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public interface Forge extends Host {
    String name();

    /**
     * Gets a HostedRepository on this Forge. This method should verify that the
     * repository exists.
     * @param name Name of repository to get
     * @return Optional containing the repository, or empty if the repository
     *         does not exist on the Forge.
     */
    Optional<HostedRepository> repository(String name);

    /**
     * Search the whole host for a commit by hash.
     * @param hash Hash to search for
     * @param includeDiffs Set to true to include parent diffs in Commit, default false
     * @return Repository name if found, otherwise empty
     */
    Optional<String> search(Hash hash, boolean includeDiffs);
    default Optional<String> search(Hash hash) {
        return search(hash, false);
    }

    /**
     * Get user by numeric ID
     */
    Optional<HostUser> userById(String id);

    /**
     * List users that are members of a group
     */
    List<HostUser> groupMembers(String group);

    /**
     * Gets the membership state for a user in a group
     */
    MemberState groupMemberState(String group, HostUser user);

    /**
     * Adds a user to a group
     */
    void addGroupMember(String group, HostUser user);

    /**
     * Some forges do not always update the "updated_at" fields of various objects
     * when the object changes. This method returns a Duration indicating how long
     * the shortest update interval is for the "updated_at" field. This is needed
     * to be taken into account when running queries (typically by padding the
     * timestamp by this duration to guarantee that no results are missed). The
     * default returns 0 which means no special considerations are needed.
     */
    default Duration minTimeStampUpdateInterval() {
        return Duration.ZERO;
    }

    static Forge from(String name, URI uri, Credential credential, JSONObject configuration) {
        var factory = ForgeFactory.getForgeFactories().stream()
                                    .filter(f -> f.name().equals(name))
                                    .findFirst();
        if (factory.isEmpty()) {
            throw new RuntimeException("No forge factory named '" + name + "' found - check module path");
        }
        return factory.get().create(uri, credential, configuration);
    }

    static Forge from(String name, URI uri, Credential credential) {
        return from(name, uri, credential, null);
    }

    static Forge from(String name, URI uri) {
        return from(name, uri, null, null);
    }

    static Forge from(String name, URI uri, JSONObject configuration) {
        return from(name, uri, null, configuration);
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
