/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.metrics;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CollectorRegistryTests {
    @Test
    void register() {
        var registry = new CollectorRegistry();
        var counter = Counter.name("counter").register(registry);
        var gauge = Gauge.name("gauge").register(registry);
        var metrics = registry.scrape();
        assertEquals(2, metrics.size());
        assertEquals("counter", metrics.get(0).name());
        assertEquals(Metric.Type.COUNTER, metrics.get(0).type());
        assertEquals("gauge", metrics.get(1).name());
        assertEquals(Metric.Type.GAUGE, metrics.get(1).type());
    }

    @Test
    void unregister() {
        var registry = new CollectorRegistry();
        var counter = Counter.name("test").register(registry);
        assertEquals(1, registry.scrape().size());
        registry.unregister(counter);
        assertEquals(0, registry.scrape().size());
    }
}
