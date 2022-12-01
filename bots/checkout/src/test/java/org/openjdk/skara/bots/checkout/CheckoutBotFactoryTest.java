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
package org.openjdk.skara.bots.checkout;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "marks": {
                    "repo": "mark",
                    "author": "test_author <test_author@test.com>"
                  },
                  "repositories": [
                    {
                      "from": {
                        "repo": "from1",
                        "branch": "master"
                      },
                      "to": "to1"
                    },
                    {
                      "from": {
                        "repo": "from2",
                        "branch": "dev"
                      },
                      "to": "to2"
                    }
                  ]
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("mark", new TestHostedRepository("mark"))
                .addHostedRepository("from1", new TestHostedRepository("from1"))
                .addHostedRepository("from2", new TestHostedRepository("from2"))
                .build();

        var bots = testBotFactory.createBots(CheckoutBotFactory.NAME, jsonConfig);
        // A checkoutBot for every configured repository
        assertEquals(2, bots.size());

        assertEquals("CheckoutBot(from1:master, to1)", bots.get(0).toString());
        assertEquals("CheckoutBot(from2:dev, to2)", bots.get(1).toString());
    }
}