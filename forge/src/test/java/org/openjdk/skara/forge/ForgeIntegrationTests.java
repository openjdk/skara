/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.forge.github.GitHubApplication;
import org.openjdk.skara.forge.github.GitHubHost;
import org.openjdk.skara.forge.gitlab.GitLabHost;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.TestProperties;
import org.openjdk.skara.test.EnabledIfTestProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeIntegrationTests {
    private static TestProperties props;

    @BeforeAll
    static void beforeAll() {
        HttpProxy.setup();
        props = TestProperties.load();
    }

    @Test
    @EnabledIfTestProperties({"github.app.id", "github.app.installation", "github.app.key.file", "github.repository"})
    void gitHubLabels() throws IOException {
        var uri = URIBuilder.base("https://github.com/").build();
        var id = props.get("github.app.id");
        var installation = props.get("github.app.installation");
        var keyFile = Paths.get(props.get("github.app.key.file"));

        var keyContents = Files.readString(keyFile);
        var app = new GitHubApplication(keyContents, id, installation);
        var gitHubHost = new GitHubHost(uri, app, null, null, null, Set.of());

        var repo = gitHubHost.repository(props.get("github.repository")).orElseThrow();

        verifyLabels(repo, true);
    }

    @Test
    @EnabledIfTestProperties({"gitlab.uri", "gitlab.user", "gitlab.pat", "gitlab.repository"})
    void gitLabLabels() throws IOException {
        var uri = URIBuilder.base(props.get("gitlab.uri")).build();
        var user = props.get("gitlab.user");
        var pat = props.get("gitlab.pat");
        var credential = new Credential(user, pat);

        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, List.of());

        var repo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();

        verifyLabels(repo, false);
    }

    private void verifyLabels(HostedRepository repo, boolean supportsDeleteDescription) {
        var labels = repo.labels();
        var labelName = "skara-test-label";
        var label1 = new Label(labelName, "bar");
        // If the label is already there
        if (labels.stream().anyMatch(l -> l.name().equals(labelName))) {
            repo.deleteLabel(label1);
        }
        repo.addLabel(label1);
        labels = repo.labels();
        assertTrue(labels.contains(label1));

        var label2 = new Label(labelName, "new description");
        repo.updateLabel(label2);
        labels = repo.labels();
        assertTrue(labels.contains(label2));
        assertFalse(labels.contains(label1));

        var label3 = new Label(labelName, null);
        if (supportsDeleteDescription) {
            repo.updateLabel(label3);
            labels = repo.labels();
            assertTrue(labels.contains(label3));
            assertFalse(labels.contains(label2));
            assertFalse(labels.contains(label1));
        }

        repo.deleteLabel(label3);
        labels = repo.labels();
        assertFalse(labels.contains(label3));
        assertFalse(labels.contains(label2));
        assertFalse(labels.contains(label1));
    }
}
