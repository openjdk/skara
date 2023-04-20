/*
 * Copyright (c) 2022, 2023 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "external": {
                        "pr": {
                          "test": "used to run tests"
                        },
                        "commit": {
                          "command1": "test1",
                          "command2": "test2"
                        }
                      },
                      "exclude-commit-comments-from": [
                          1,
                          2
                      ],
                      "blockers": {
                        "test": "Signature needs verify"
                      },
                      "ready": {
                        "labels": [],
                        "comments": []
                      },
                      "labels": {
                        "label1": {
                          "repository": "repo1:master",
                          "filename": "file.json"
                        }
                      },
                      "repositories": {
                        "repo2": {
                          "census": "census:master",
                          "censuslink": "https://test.test.com",
                          "issues": "TEST",
                          "csr": true,
                          "merge": false,
                          "two-reviewers": [
                            "rfr"
                          ],
                          "24h": [
                            "24h_test"
                          ],
                          "integrators": [
                            "integrator1",
                            "integrator2"
                          ],
                          "reviewCleanBackport": true
                        },
                        "repo5": {
                          "census": "census:master",
                          "censuslink": "https://test.test.com",
                          "issues": "TEST2",
                          "csr": true,
                          "merge": true,
                          "two-reviewers": [
                            "rfr"
                          ],
                          "24h": [
                            "24h_test"
                          ],
                          "integrators": [
                            "integrator1",
                            "integrator2"
                          ],
                          "reviewCleanBackport": true,
                          "processCommit": false
                        },
                        "repo6": {
                          "census": "census:master",
                          "censuslink": "https://test.test.com",
                          "issues": "TEST2",
                          "csr": false,
                          "two-reviewers": [
                            "rfr"
                          ],
                          "24h": [
                            "24h_test"
                          ],
                          "integrators": [
                            "integrator1",
                            "integrator2"
                          ],
                          "reviewCleanBackport": true,
                          "reviewMerge": true,
                          "processPR": false
                        },
                        "repo7": {
                          "census": "census:master",
                          "censuslink": "https://test.test.com",
                          "issues": "TEST3",
                          "two-reviewers": [
                            "rfr"
                          ],
                          "24h": [
                            "24h_test"
                          ],
                          "integrators": [
                            "integrator1",
                            "integrator2"
                          ],
                          "reviewCleanBackport": true,
                          "reviewMerge": true,
                          "processPR": false
                        }
                      },
                      "forks": {
                        "repo3": "fork3",
                        "repo4": "fork4",
                      },
                      "mlbridge": "mlbridge[bot]"
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                    .addHostedRepository("repo2", new TestHostedRepository(TestHost.createNew(List.of()), "repo2"))
                    .addHostedRepository("repo3", new TestHostedRepository("repo3"))
                    .addHostedRepository("repo4", new TestHostedRepository("repo4"))
                    .addHostedRepository("repo5", new TestHostedRepository(TestHost.createNew(List.of()), "repo5"))
                    .addHostedRepository("repo6", new TestHostedRepository(TestHost.createNew(List.of()), "repo6"))
                    .addHostedRepository("repo7", new TestHostedRepository(TestHost.createNew(List.of()), "repo7"))
                    .addHostedRepository("fork3", new TestHostedRepository("fork3"))
                    .addHostedRepository("fork4", new TestHostedRepository("fork4"))
                    .addHostedRepository("census", new TestHostedRepository("census"))
                    .addIssueProject("TEST", new TestIssueProject(TestHost.createNew(List.of()), "TEST"))
                    .addIssueProject("TEST2", new TestIssueProject(TestHost.createNew(List.of()), "TEST2"))
                    .addIssueProject("TEST3", new TestIssueProject(TestHost.createNew(List.of()), "TEST3"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(PullRequestBotFactory.NAME, jsonConfig);
            // A pullRequestBot for every configured repository and A CSRIssueBot for every configured issue project with any repo configured with 'csr: true'
            // No CSRIssueBot created for issueTracker TEST3 because it is not associated with any CSR enabled repo
            assertEquals(6, bots.size());

            var pullRequestBot0 = (PullRequestBot) bots.get(0);
            assertEquals("PullRequestBot@repo6", pullRequestBot0.toString());
            assertEquals("used to run tests", pullRequestBot0.externalPullRequestCommands().get("test"));
            assertEquals("TEST2", pullRequestBot0.issueProject().name());
            assertEquals("census", pullRequestBot0.censusRepo().name());
            assertEquals("master", pullRequestBot0.censusRef());
            assertEquals("{test=used to run tests}", pullRequestBot0.externalPullRequestCommands().toString());
            assertEquals("{test=Signature needs verify}", pullRequestBot0.blockingCheckLabels().toString());
            assertEquals("[rfr]", pullRequestBot0.twoReviewersLabels().toString());
            assertEquals("[24h_test]", pullRequestBot0.twentyFourHoursLabels().toString());
            assertFalse(pullRequestBot0.ignoreStaleReviews());
            assertEquals(".*", pullRequestBot0.allowedTargetBranches().toString());
            var integrators = pullRequestBot0.integrators();
            assertEquals(2, integrators.size());
            assertTrue(integrators.contains("integrator1"));
            assertTrue(integrators.contains("integrator2"));
            assertTrue(pullRequestBot0.reviewCleanBackport());
            assertTrue(pullRequestBot0.reviewMerge());
            assertEquals("mlbridge[bot]", pullRequestBot0.mlbridgeBotName());
            assertTrue(pullRequestBot0.enableMerge());

            var pullRequestBot1 = (PullRequestBot) bots.get(1);
            assertEquals("PullRequestBot@repo7", pullRequestBot1.toString());

            var pullRequestBot2 = (PullRequestBot) bots.get(2);
            assertEquals("PullRequestBot@repo5", pullRequestBot2.toString());
            assertTrue(pullRequestBot2.enableMerge());

            var pullRequestBot3 = (PullRequestBot) bots.get(3);
            assertEquals("PullRequestBot@repo2", pullRequestBot3.toString());
            assertFalse(pullRequestBot3.enableMerge());

            var csrIssueBot1 = (CSRIssueBot) bots.get(4);
            assertEquals(1, csrIssueBot1.repositories().size());
            assertNotNull(csrIssueBot1.getPRBot("repo5"));

            var csrIssueBot2 = (CSRIssueBot) bots.get(5);
            assertEquals(1, csrIssueBot2.repositories().size());
            assertNotNull(csrIssueBot2.getPRBot("repo2"));
        }
    }
}
