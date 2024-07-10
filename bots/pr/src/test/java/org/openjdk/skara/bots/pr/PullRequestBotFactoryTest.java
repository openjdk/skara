/*
 * Copyright (c) 2022, 2024, Oracle and/or its affiliates. All rights reserved.
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
                          "backport": false,
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
                          "reviewCleanBackport": true,
                          "mergeSources": [
                            "openjdk/playground",
                            "openjdk/skara",
                          ]
                        },
                        "repo5": {
                          "census": "census:master",
                          "censuslink": "https://test.test.com",
                          "issues": "TEST2",
                          "csr": true,
                          "backport": true,
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
                          "reviewMerge": "always",
                          "processPR": false,
                          "jcheckMerge": true,
                          "versionMismatchWarning": false,
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
                          "reviewMerge": "always",
                          "processPR": false,
                          "jcheckMerge": false
                          "approval": {
                            "request": "-critical-request",
                            "approved": "-critical-approved",
                            "rejected": "-critical-rejected",
                            "documentLink": "https://example.com",
                            "branches": {
                              "jdk20.0.1": { "prefix": "CPU23_04" },
                              "jdk20.0.2": { "prefix": "CPU23_05" },
                              }
                          },
                          "versionMismatchWarning": true,
                          "cleanCommandEnabled": false,
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
            // A IssueBot for every configured issue project
            // No CSRIssueBot created for issueTracker TEST3 because it is not associated with any CSR enabled repo
            assertEquals(9, bots.size());

            var pullRequestBot2 = (PullRequestBot) bots.stream()
                    .filter(bot -> bot.toString().equals("PullRequestBot@repo2"))
                    .findFirst().orElseThrow();
            assertEquals("PullRequestBot@repo2", pullRequestBot2.toString());
            assertFalse(pullRequestBot2.enableMerge());
            assertTrue(pullRequestBot2.mergeSources().contains("openjdk/skara"));
            assertTrue(pullRequestBot2.mergeSources().contains("openjdk/playground"));
            assertFalse(pullRequestBot2.jcheckMerge());
            assertFalse(pullRequestBot2.enableBackport());

            var pullRequestBot5 = (PullRequestBot) bots.stream()
                    .filter(bot -> bot.toString().equals("PullRequestBot@repo5"))
                    .findFirst().orElseThrow();
            assertEquals("PullRequestBot@repo5", pullRequestBot5.toString());
            assertTrue(pullRequestBot5.enableMerge());
            assertFalse(pullRequestBot5.jcheckMerge());
            assertTrue(pullRequestBot5.enableBackport());
            assertFalse(pullRequestBot5.versionMismatchWarning());
            assertTrue(pullRequestBot5.cleanCommandEnabled());

            var pullRequestBot6 = (PullRequestBot) bots.stream()
                    .filter(bot -> bot.toString().equals("PullRequestBot@repo6"))
                    .findFirst().orElseThrow();
            assertEquals("PullRequestBot@repo6", pullRequestBot6.toString());
            assertEquals("used to run tests", pullRequestBot6.externalPullRequestCommands().get("test"));
            assertEquals("TEST2", pullRequestBot6.issueProject().name());
            assertEquals("census", pullRequestBot6.censusRepo().name());
            assertEquals("master", pullRequestBot6.censusRef());
            assertEquals("{test=used to run tests}", pullRequestBot6.externalPullRequestCommands().toString());
            assertEquals("{test=Signature needs verify}", pullRequestBot6.blockingCheckLabels().toString());
            assertEquals("[rfr]", pullRequestBot6.twoReviewersLabels().toString());
            assertEquals("[24h_test]", pullRequestBot6.twentyFourHoursLabels().toString());
            assertTrue(pullRequestBot6.useStaleReviews());
            assertEquals(".*", pullRequestBot6.allowedTargetBranches().toString());
            var integrators = pullRequestBot6.integrators();
            assertEquals(2, integrators.size());
            assertTrue(integrators.contains("integrator1"));
            assertTrue(integrators.contains("integrator2"));
            assertTrue(pullRequestBot6.reviewCleanBackport());
            assertEquals(MergePullRequestReviewConfiguration.ALWAYS, pullRequestBot6.reviewMerge());
            assertEquals("mlbridge[bot]", pullRequestBot6.mlbridgeBotName());
            assertTrue(pullRequestBot6.enableMerge());
            assertTrue(pullRequestBot6.jcheckMerge());
            assertTrue(pullRequestBot6.enableBackport());
            assertFalse(pullRequestBot6.versionMismatchWarning());

            var pullRequestBot7 = (PullRequestBot) bots.stream()
                    .filter(bot -> bot.toString().equals("PullRequestBot@repo7"))
                    .findFirst().orElseThrow();
            assertEquals("PullRequestBot@repo7", pullRequestBot7.toString());
            assertFalse(pullRequestBot7.jcheckMerge());
            assertEquals("https://example.com", pullRequestBot7.approval().documentLink());
            assertTrue(pullRequestBot7.versionMismatchWarning());
            assertFalse(pullRequestBot7.cleanCommandEnabled());

            var csrIssueBot1 = (CSRIssueBot) bots.stream()
                    .filter(bot -> bot.toString().equals("CSRIssueBot@TEST"))
                    .findFirst().orElseThrow();
            // repo5 and repo6 are both configured with issueProject TEST2, but only repo5 is enabled csr
            assertEquals(1, csrIssueBot1.repositories().size());
            assertNotNull(csrIssueBot1.getPRBot("repo5"));
            assertEquals("CSRIssueBot@TEST", csrIssueBot1.toString());

            var csrIssueBot2 = (CSRIssueBot) bots.stream()
                    .filter(bot -> bot.toString().equals("CSRIssueBot@TEST2"))
                    .findFirst().orElseThrow();
            assertEquals(1, csrIssueBot2.repositories().size());
            assertNotNull(csrIssueBot2.getPRBot("repo2"));
            assertEquals("CSRIssueBot@TEST2", csrIssueBot2.toString());

            var issueBot1 = (IssueBot) bots.stream()
                    .filter(bot -> bot.toString().equals("IssueBot@TEST"))
                    .findFirst().orElseThrow();
            assertEquals("IssueBot@TEST", issueBot1.toString());
            // repo2 is configured with issueProject TEST
            assertEquals(1, issueBot1.repositories().size());

            var issueBot2 = (IssueBot) bots.stream()
                    .filter(bot -> bot.toString().equals("IssueBot@TEST2"))
                    .findFirst().orElseThrow();
            assertEquals("IssueBot@TEST2", issueBot2.toString());
            // repo5 and repo6 are both configured with issueProject TEST2
            assertEquals(2, issueBot2.repositories().size());

            var issueBot3 = (IssueBot) bots.stream()
                    .filter(bot -> bot.toString().equals("IssueBot@TEST3"))
                    .findFirst().orElseThrow();
            assertEquals("IssueBot@TEST3", issueBot3.toString());
            // repo7 is configured with issueProject TEST3
            assertEquals(1, issueBot3.repositories().size());

            // prBot for repo2, issueBot for TEST and csrIssueBot for TEST should share the same map
            assertSame(pullRequestBot2.issuePRMap(), issueBot1.issuePRMap());
            assertSame(pullRequestBot2.issuePRMap(), csrIssueBot1.issuePRMap());
            // prBot for repo5, repo6, issueBot for TEST2 and csrIssueBot for TEST2 should share the same map
            assertSame(pullRequestBot6.issuePRMap(), pullRequestBot5.issuePRMap());
            assertSame(pullRequestBot6.issuePRMap(), issueBot2.issuePRMap());
            assertSame(pullRequestBot6.issuePRMap(), csrIssueBot2.issuePRMap());
            // prBot for repo7 and issueBot for TEST3 should share the same map
            assertSame(pullRequestBot7.issuePRMap(), issueBot3.issuePRMap());

            assertNotSame(pullRequestBot6.issuePRMap(), pullRequestBot2.issuePRMap());
            assertNotSame(pullRequestBot6.issuePRMap(), pullRequestBot7.issuePRMap());
            assertNotSame(pullRequestBot7.issuePRMap(), pullRequestBot2.issuePRMap());
        }
    }
}
