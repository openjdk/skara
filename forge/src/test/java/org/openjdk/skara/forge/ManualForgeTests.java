/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.forge.github.GitHubApplication;
import org.openjdk.skara.forge.github.GitHubHost;
import org.openjdk.skara.forge.gitlab.GitLabHost;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.ManualTestSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This class contains manual tests for interactions with different forges
 * (GitHub and GitLab). To be able to run them, you need to provide a
 * properties file with the necessary connection details for running these
 * tests. See ManualTestSettings.
 *
 * To be able to run the tests, you need to remove or comment out the @Disabled
 * annotation first.
 */
@Disabled("Manual")
public class ManualForgeTests {

    @Test
    void gitHubLabels() throws IOException {
        HttpProxy.setup();
        var settings = ManualTestSettings.loadManualTestSettings();
        var uri = URIBuilder.base("https://github.com/").build();
        var id = settings.getProperty("github.app.id");
        var installation = settings.getProperty("github.app.installation");
        var keyFile = Paths.get(settings.getProperty("github.app.key.file"));

        var keyContents = Files.readString(keyFile, StandardCharsets.UTF_8);
        var app = new GitHubApplication(keyContents, id, installation);
        var gitHubHost = new GitHubHost(uri, app, null, null, null, Set.of());

        var repo = gitHubHost.repository(settings.getProperty("github.repository")).orElseThrow();

        verifyLabels(repo, true);
    }

    @Test
    void gitLabLabels() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var user = settings.getProperty("gitlab.user");
        var pat = settings.getProperty("gitlab.pat");
        var credential = new Credential(user, pat);

        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, List.of());

        var repo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();

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
