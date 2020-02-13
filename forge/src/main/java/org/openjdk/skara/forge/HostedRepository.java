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
package org.openjdk.skara.forge;

import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

public interface HostedRepository {
    Forge forge();
    PullRequest createPullRequest(HostedRepository target,
                                  String targetRef,
                                  String sourceRef,
                                  String title,
                                  List<String> body,
                                  boolean draft);
    PullRequest pullRequest(String id);

    /**
     * Returns a list of all open pull requests.
     */
    List<PullRequest> pullRequests();

    /**
     * Returns a list of all pull requests (both open and closed) that have been updated after the
     * provided time, ordered by latest updated first. If there are many pull requests that
     * match, the list may have been truncated.
     */
    List<PullRequest> pullRequests(ZonedDateTime updatedAfter);
    List<PullRequest> findPullRequestsWithComment(String author, String body);
    Optional<PullRequest> parsePullRequestUrl(String url);
    String name();
    Optional<HostedRepository> parent();
    URI url();
    URI webUrl();
    URI webUrl(Hash hash);
    VCS repositoryType();
    String fileContents(String filename, String ref);
    String namespace();
    Optional<WebHook> parseWebHook(JSONValue body);
    HostedRepository fork();
    long id();
    Hash branchHash(String ref);

    default PullRequest createPullRequest(HostedRepository target,
                                          String targetRef,
                                          String sourceRef,
                                          String title,
                                          List<String> body) {
        return createPullRequest(target, targetRef, sourceRef, title, body, false);
    }
}
