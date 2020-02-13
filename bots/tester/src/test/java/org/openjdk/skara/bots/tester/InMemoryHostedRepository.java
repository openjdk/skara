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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

class InMemoryHostedRepository implements HostedRepository {
    Forge host;
    URI webUrl;
    URI url;
    long id;

    @Override
    public Forge forge() {
        return host;
    }

    @Override
    public PullRequest createPullRequest(HostedRepository target,
                                         String targetRef,
                                         String sourceRef,
                                         String title,
                                         List<String> body,
                                         boolean draft) {
        return null;
    }

    @Override
    public PullRequest pullRequest(String id) {
        return null;
    }

    @Override
    public List<PullRequest> pullRequests() {
        return null;
    }

    @Override
    public List<PullRequest> pullRequests(ZonedDateTime updatedAfter) {
        return null;
    }

    @Override
    public Optional<PullRequest> parsePullRequestUrl(String url) {
        return null;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public Optional<HostedRepository> parent() {
        return null;
    }

    @Override
    public URI url() {
        return url;
    }

    @Override
    public URI webUrl() {
        return webUrl;
    }

    @Override
    public URI webUrl(Hash hash) {
        return null;
    }

    @Override
    public VCS repositoryType() {
        return null;
    }

    @Override
    public String fileContents(String filename, String ref) {
        return null;
    }

    @Override
    public String namespace() {
        return null;
    }

    @Override
    public Optional<WebHook> parseWebHook(JSONValue body) {
        return null;
    }

    @Override
    public HostedRepository fork() {
        return null;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public Hash branchHash(String ref) {
        return null;
    }

    @Override
    public List<PullRequest> findPullRequestsWithComment(String author, String body) {
        return null;
    }
}
