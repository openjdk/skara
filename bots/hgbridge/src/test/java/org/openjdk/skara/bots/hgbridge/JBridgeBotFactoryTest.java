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
package org.openjdk.skara.bots.hgbridge;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class JBridgeBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "marks": {
                        "repository": "marks",
                        "ref": "master",
                        "name": "test",
                        "email": "test@test.org"
                      },
                      "converters": [
                        {
                          "repository": "converter",
                          "ref": "master",
                          "authors": "test_authors.json",
                          "contributors": "test_contributors.json",
                          "sponsors": "test_sponsors.json",
                          "corrections": "test_corrections.json",
                          "replacements": "test_replacements.json",
                          "repositories": [
                            {
                              "source": "https://test.org/source1",
                              "destinations": "https://test.org/des1",
                              "replacements": "test_replacements_for_repo1.json",
                              "corrections": "test_corrections_for_repo1.json"
                            },
                            {
                              "source": "https://test.org/source2",
                              "destinations": "https://test.org/des2",
                              "sponsors": "test_sponsors_for_repo2.json",
                              "authors": "test_authors_for_repo2.json",
                              "replacements": "test_replacements_for_repo2.json",
                            }
                          ]
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("marks", new TestHostedRepository("marks"))
                    .addHostedRepository("converter", new TestHostedRepository("converter"))
                    .addHostedRepository("https://test.org/des1", new TestHostedRepository("des1"))
                    .addHostedRepository("https://test.org/des2", new TestHostedRepository("des2"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(JBridgeBotFactory.NAME, jsonConfig);
            // A JBridgeBot for every configured repo
            assertEquals(2, bots.size());

            JBridgeBot jBridgeBot1 = (JBridgeBot) bots.get(0);
            assertEquals("JBridgeBot@https://test.org/source1", jBridgeBot1.toString());
            var exporterConfig1 = jBridgeBot1.getExporterConfig();
            assertEquals("marks", exporterConfig1.marksRepo().name());
            assertEquals("master", exporterConfig1.marksRef());
            assertEquals("test@test.org", exporterConfig1.marksAuthorEmail());
            assertEquals("test", exporterConfig1.marksAuthorName());
            assertEquals("des1", exporterConfig1.destinations().get(0).name());
            assertEquals("https://test.org/source1", exporterConfig1.source().toString());
            assertEquals("master", exporterConfig1.getConfigurationRef());
            assertEquals("test_authors.json", exporterConfig1.getAuthorsFile().get(0));
            assertEquals("test_contributors.json", exporterConfig1.getContributorsFile().get(0));
            assertEquals("test_sponsors.json", exporterConfig1.getSponsorsFile().get(0));
            assertEquals("test_corrections.json", exporterConfig1.getCorrectionsFile().get(0));
            assertEquals("test_corrections_for_repo1.json", exporterConfig1.getCorrectionsFile().get(1));
            assertEquals("test_replacements.json", exporterConfig1.getReplacementsFile().get(0));
            assertEquals("test_replacements_for_repo1.json", exporterConfig1.getReplacementsFile().get(1));

            JBridgeBot jBridgeBot2 = (JBridgeBot) bots.get(1);
            assertEquals("JBridgeBot@https://test.org/source2", jBridgeBot2.toString());
            var exporterConfig2 = jBridgeBot2.getExporterConfig();
            assertEquals("marks", exporterConfig2.marksRepo().name());
            assertEquals("master", exporterConfig2.marksRef());
            assertEquals("test@test.org", exporterConfig2.marksAuthorEmail());
            assertEquals("test", exporterConfig2.marksAuthorName());
            assertEquals("des2", exporterConfig2.destinations().get(0).name());
            assertEquals("https://test.org/source2", exporterConfig2.source().toString());
            assertEquals("master", exporterConfig2.getConfigurationRef());
            assertEquals("test_authors.json", exporterConfig2.getAuthorsFile().get(0));
            assertEquals("test_authors_for_repo2.json", exporterConfig2.getAuthorsFile().get(1));
            assertEquals("test_contributors.json", exporterConfig2.getContributorsFile().get(0));
            assertEquals("test_sponsors.json", exporterConfig2.getSponsorsFile().get(0));
            assertEquals("test_sponsors_for_repo2.json", exporterConfig2.getSponsorsFile().get(1));
            assertEquals("test_corrections.json", exporterConfig2.getCorrectionsFile().get(0));
            assertEquals("test_replacements.json", exporterConfig2.getReplacementsFile().get(0));
            assertEquals("test_replacements_for_repo2.json", exporterConfig2.getReplacementsFile().get(1));
        }
    }
}