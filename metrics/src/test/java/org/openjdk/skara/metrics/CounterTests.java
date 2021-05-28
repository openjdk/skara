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

class CounterTests {
    @Test
    void inc() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").register(registry);
        assertEquals(0, counter.collect().get(0).value());
        counter.inc();
        assertEquals(1, counter.collect().get(0).value());
    }

    @Test
    void incTwice() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").register(registry);
        assertEquals(0, counter.collect().get(0).value());
        counter.inc();
        assertEquals(1, counter.collect().get(0).value());
        counter.inc();
        assertEquals(2, counter.collect().get(0).value());
        counter.inc();
        counter.inc();
        assertEquals(4, counter.collect().get(0).value());
    }

    @Test
    void incWithValue() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").register(registry);
        assertEquals(0, counter.collect().get(0).value());
        counter.inc(17);
        assertEquals(17, counter.collect().get(0).value());
    }

    @Test
    void incWithValueMixedWithInc() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").register(registry);
        assertEquals(0, counter.collect().get(0).value());
        counter.inc(17);
        assertEquals(17, counter.collect().get(0).value());
        counter.inc();
        assertEquals(18, counter.collect().get(0).value());
        counter.inc(3);
        assertEquals(21, counter.collect().get(0).value());
    }

    @Test
    void incAndReset() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").register(registry);
        assertEquals(0, counter.collect().get(0).value());
        counter.inc(17);
        assertEquals(17, counter.collect().get(0).value());
        counter.reset();
        assertEquals(0, counter.collect().get(0).value());
        counter.inc(3);
        assertEquals(3, counter.collect().get(0).value());
    }

    @Test
    void oneLabel() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").labels("a").register(registry);
        counter.labels("1").inc(17);
        assertEquals(1, counter.collect().size());
        assertEquals(17, counter.collect().get(0).value());
        assertEquals(1, counter.collect().get(0).labels().size());
        assertEquals("a", counter.collect().get(0).labels().get(0).name());
        assertEquals("1", counter.collect().get(0).labels().get(0).value());
    }

    @Test
    void twoLabels() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").labels("a", "b").register(registry);
        counter.labels("1", "2").inc(17);
        assertEquals(1, counter.collect().size());
        assertEquals(17, counter.collect().get(0).value());
        assertEquals(2, counter.collect().get(0).labels().size());
        assertEquals("a", counter.collect().get(0).labels().get(0).name());
        assertEquals("1", counter.collect().get(0).labels().get(0).value());
        assertEquals("b", counter.collect().get(0).labels().get(1).name());
        assertEquals("2", counter.collect().get(0).labels().get(1).value());
    }

    @Test
    void threeLabels() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").labels("a", "b", "c").register(registry);
        counter.labels("1", "2", "3").inc(17);
        assertEquals(1, counter.collect().size());
        assertEquals(17, counter.collect().get(0).value());
        assertEquals(3, counter.collect().get(0).labels().size());
        assertEquals("a", counter.collect().get(0).labels().get(0).name());
        assertEquals("1", counter.collect().get(0).labels().get(0).value());
        assertEquals("b", counter.collect().get(0).labels().get(1).name());
        assertEquals("2", counter.collect().get(0).labels().get(1).value());
        assertEquals("c", counter.collect().get(0).labels().get(2).name());
        assertEquals("3", counter.collect().get(0).labels().get(2).value());
    }

    @Test
    void threeLabelsIncAndReset() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").labels("a", "b", "c").register(registry);
        counter.labels("1", "2", "3").inc();
        assertEquals(1, counter.collect().get(0).value());
        counter.labels("1", "2", "3").inc(17);
        assertEquals(18, counter.collect().get(0).value());
        counter.labels("1", "2", "3").reset();
        assertEquals(0, counter.collect().get(0).value());
        counter.labels("1", "2", "3").inc(37);
        assertEquals(37, counter.collect().get(0).value());
    }

    @Test
    void oneLabelMultiple() {
        var registry = new CollectorRegistry(false, false);
        var counter = Counter.name("test").labels("a").register(registry);
        counter.labels("1").inc(17);
        counter.labels("2").inc(19);
        assertEquals(2, counter.collect().size());
        var values = counter.collect().stream().map(l -> l.value()).toList();
        assertTrue(values.contains(17.0));
        assertTrue(values.contains(19.0));
    }
}
