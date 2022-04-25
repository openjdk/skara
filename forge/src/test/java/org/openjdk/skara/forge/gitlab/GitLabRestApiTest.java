/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge.gitlab;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.ManualTestSettings;
import org.openjdk.skara.vcs.Hash;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * To be able to run the tests, you need to remove or comment out the @Disabled annotation first.
 */
//@Disabled("Manual")
public class GitLabRestApiTest {

    @Test
    void testReviews() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var username = settings.getProperty("gitlab.user");
        var token = settings.getProperty("gitlab.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, Set.of());
        var gitLabRepo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(settings.getProperty("gitlab.merge.request.id"));

        var reviewList = gitLabMergeRequest.reviews();
        var actualHash = reviewList.get(0).hash().orElse(new Hash(""));
        assertEquals(settings.getProperty("gitlab.review.hash"), actualHash.hex());
    }
}
