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

class GaugeTests {
    @Test
    void inc() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").register(registry);
        gauge.inc();
        assertEquals(1, gauge.collect().get(0).value());
    }

    @Test
    void dec() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").register(registry);
        gauge.inc();
        assertEquals(1, gauge.collect().get(0).value());
        gauge.dec();
        assertEquals(0, gauge.collect().get(0).value());
        gauge.dec();
        assertEquals(-1, gauge.collect().get(0).value());
    }

    @Test
    void incWithValue() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").register(registry);
        gauge.inc(17);
        assertEquals(17, gauge.collect().get(0).value());
    }

    @Test
    void decWithValue() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").register(registry);
        gauge.dec(17);
        assertEquals(-17, gauge.collect().get(0).value());
    }

    @Test
    void incAndDecWithValue() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").register(registry);
        gauge.inc(17);
        assertEquals(17, gauge.collect().get(0).value());
        gauge.dec(20);
        assertEquals(-3, gauge.collect().get(0).value());
    }

    @Test
    void set() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").register(registry);
        gauge.set(1337);
        assertEquals(1337, gauge.collect().get(0).value());
        gauge.set(17);
        assertEquals(17, gauge.collect().get(0).value());
    }

    @Test
    void oneLabel() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").labels("a").register(registry);
        gauge.labels("1").inc();
        assertEquals(1, gauge.collect().size());
        assertEquals(1, gauge.collect().get(0).value());
        assertEquals(1, gauge.collect().get(0).labels().size());
        assertEquals("a", gauge.collect().get(0).labels().get(0).name());
        assertEquals("1", gauge.collect().get(0).labels().get(0).value());
    }

    @Test
    void oneLabelIncDecSet() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").labels("a").register(registry);
        gauge.labels("1").inc(17);
        assertEquals(1, gauge.collect().size());
        assertEquals(17, gauge.collect().get(0).value());
        assertEquals(1, gauge.collect().get(0).labels().size());
        assertEquals("a", gauge.collect().get(0).labels().get(0).name());
        assertEquals("1", gauge.collect().get(0).labels().get(0).value());
        gauge.labels("1").dec(20);
        assertEquals(-3, gauge.collect().get(0).value());
        gauge.labels("1").set(1337);
        assertEquals(1337, gauge.collect().get(0).value());
    }

    @Test
    void twoLabels() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").labels("a", "b").register(registry);
        gauge.labels("1", "2").inc();
        assertEquals(1, gauge.collect().size());
        assertEquals(1, gauge.collect().get(0).value());
        assertEquals(2, gauge.collect().get(0).labels().size());
        assertEquals("a", gauge.collect().get(0).labels().get(0).name());
        assertEquals("1", gauge.collect().get(0).labels().get(0).value());
        assertEquals("b", gauge.collect().get(0).labels().get(1).name());
        assertEquals("2", gauge.collect().get(0).labels().get(1).value());
    }

    @Test
    void twoLabelsIncDecSet() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").labels("a", "b").register(registry);
        gauge.labels("1", "2").inc(17);
        assertEquals(1, gauge.collect().size());
        assertEquals(17, gauge.collect().get(0).value());
        assertEquals(2, gauge.collect().get(0).labels().size());
        assertEquals("a", gauge.collect().get(0).labels().get(0).name());
        assertEquals("1", gauge.collect().get(0).labels().get(0).value());
        assertEquals("b", gauge.collect().get(0).labels().get(1).name());
        assertEquals("2", gauge.collect().get(0).labels().get(1).value());
        gauge.labels("1", "2").dec(20);
        assertEquals(-3, gauge.collect().get(0).value());
        gauge.labels("1", "2").set(1337);
        assertEquals(1337, gauge.collect().get(0).value());
    }

    @Test
    void threeLabels() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").labels("a", "b", "c").register(registry);
        gauge.labels("1", "2", "3").inc();
        assertEquals(1, gauge.collect().size());
        assertEquals(1, gauge.collect().get(0).value());
        assertEquals(3, gauge.collect().get(0).labels().size());
        assertEquals("a", gauge.collect().get(0).labels().get(0).name());
        assertEquals("1", gauge.collect().get(0).labels().get(0).value());
        assertEquals("b", gauge.collect().get(0).labels().get(1).name());
        assertEquals("2", gauge.collect().get(0).labels().get(1).value());
        assertEquals("c", gauge.collect().get(0).labels().get(2).name());
        assertEquals("3", gauge.collect().get(0).labels().get(2).value());
    }

    @Test
    void threeLabelsIncDecSet() {
        var registry = new CollectorRegistry(false);
        var gauge = Gauge.name("test").labels("a", "b", "c").register(registry);
        gauge.labels("1", "2", "3").inc(17);
        assertEquals(1, gauge.collect().size());
        assertEquals(17, gauge.collect().get(0).value());
        assertEquals(3, gauge.collect().get(0).labels().size());
        assertEquals("a", gauge.collect().get(0).labels().get(0).name());
        assertEquals("1", gauge.collect().get(0).labels().get(0).value());
        assertEquals("b", gauge.collect().get(0).labels().get(1).name());
        assertEquals("2", gauge.collect().get(0).labels().get(1).value());
        assertEquals("c", gauge.collect().get(0).labels().get(2).name());
        assertEquals("3", gauge.collect().get(0).labels().get(2).value());
        gauge.labels("1", "2", "3").dec(20);
        assertEquals(-3, gauge.collect().get(0).value());
        gauge.labels("1", "2", "3").set(1337);
        assertEquals(1337, gauge.collect().get(0).value());
    }
}
