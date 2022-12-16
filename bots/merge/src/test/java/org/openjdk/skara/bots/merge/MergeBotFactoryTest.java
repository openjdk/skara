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
package org.openjdk.skara.bots.merge;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import java.time.DayOfWeek;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.*;

class MergeBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "target": "target",
                          "fork": "fork",
                          "spec": [
                            {
                              "from": "from1:master",
                              "to": "master",
                              "frequency": {
                                "interval": "weekly",
                                "weekday": "monday",
                                "hour": 3
                              }
                            },
                            {
                              "name": "spec2",
                              "from": "from2:master",
                              "to": "test"
                            },
                            {
                              "from": "from3:master",
                              "to": "master",
                              "frequency": {
                                "interval": "hourly",
                                "minute": 30
                              }
                            },
                            {
                              "from": "from4:master",
                              "to": "master",
                              "frequency": {
                                "interval": "daily",
                                "hour": 2
                              }
                            },
                            {
                              "from": "from5:master",
                              "to": "master",
                              "frequency": {
                                "interval": "monthly",
                                "day": 1,
                                "hour": 2
                              }
                            },
                            {
                              "from": "from6:master",
                              "to": "master",
                              "frequency": {
                                "interval": "yearly",
                                "month": "october",
                                "day": 15,
                                "hour": 5
                              }
                            }
                          ]
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("target", new TestHostedRepository("target"))
                    .addHostedRepository("fork", new TestHostedRepository("fork"))
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("from2", new TestHostedRepository("from2"))
                    .addHostedRepository("from3", new TestHostedRepository("from3"))
                    .addHostedRepository("from4", new TestHostedRepository("from4"))
                    .addHostedRepository("from5", new TestHostedRepository("from5"))
                    .addHostedRepository("from6", new TestHostedRepository("from6"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(MergeBotFactory.NAME, jsonConfig);
            assertEquals(1, bots.size());

            MergeBot mergeBot = (MergeBot) bots.get(0);
            assertEquals("MergeBot@(target)", mergeBot.toString());

            // Check the contents in the mergeBot
            var specs = mergeBot.getSpecs();
            MergeBot.Spec spec1 = specs.get(0);
            MergeBot.Spec.Frequency frequency1 = spec1.frequency().get();
            assertTrue(spec1.name().isEmpty());
            assertTrue(frequency1.isWeekly());
            assertEquals(DayOfWeek.MONDAY, frequency1.weekday());
            assertEquals(3, frequency1.hour());

            MergeBot.Spec spec2 = specs.get(1);
            assertTrue(spec2.frequency().isEmpty());
            assertTrue(spec2.name().isPresent());

            MergeBot.Spec spec3 = specs.get(2);
            MergeBot.Spec.Frequency frequency3 = spec3.frequency().get();
            assertTrue(frequency3.isHourly());
            assertEquals(30, frequency3.minute());

            MergeBot.Spec spec4 = specs.get(3);
            MergeBot.Spec.Frequency frequency4 = spec4.frequency().get();
            assertTrue(frequency4.isDaily());
            assertEquals(2, frequency4.hour());

            MergeBot.Spec spec5 = specs.get(4);
            MergeBot.Spec.Frequency frequency5 = spec5.frequency().get();
            assertTrue(frequency5.isMonthly());
            assertEquals(1, frequency5.day());
            assertEquals(2, frequency5.hour());

            MergeBot.Spec spec6 = specs.get(5);
            MergeBot.Spec.Frequency frequency6 = spec6.frequency().get();
            assertTrue(frequency6.isYearly());
            assertEquals(Month.OCTOBER, frequency6.month());
            assertEquals(15, frequency6.day());
            assertEquals(5, frequency6.hour());
        }
    }
}