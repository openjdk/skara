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

class PrometheusExporterTests {
    private static Metric metric(Metric.Type type, String name, double value, String... labelsAndValues) {
        var labels = new ArrayList<Metric.Label>();
        for (var labelAndValue : labelsAndValues) {
            var parts = labelAndValue.split("=");
            labels.add(new Metric.Label(parts[0], parts[1]));
        }
        return new Metric(type, name, labels, value);
    }

    private static Metric counter(String name, double value, String... labelsAndValues) {
        return metric(Metric.Type.COUNTER, name, value, labelsAndValues);
    }

    private static Metric gauge(String name, double value, String... labelsAndValues) {
        return metric(Metric.Type.GAUGE, name, value, labelsAndValues);
    }

    private static List<String> export(Metric... metrics) {
        return export(Arrays.asList(metrics));
    }

    private static List<String> export(List<Metric> metrics) {
        var output = new PrometheusExporter().export(metrics);
        return Arrays.asList(output.split("\n"));
    }

    @Test
    void counter() {
        var lines = export(counter("test", 17.3));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test counter", lines.get(0));
        assertEquals("test 17.3", lines.get(1));
    }

    @Test
    void counterWithOneLabel() {
        var lines = export(counter("test", 17.3, "a=1"));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test counter", lines.get(0));
        assertEquals("test{a=\"1\"} 17.3", lines.get(1));
    }

    @Test
    void counterWithTwoLabels() {
        var lines = export(counter("test", 17.3, "a=1", "b=2"));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test counter", lines.get(0));
        assertEquals("test{a=\"1\",b=\"2\"} 17.3", lines.get(1));
    }

    @Test
    void counterWithThreeLabels() {
        var lines = export(counter("test", 17.3, "a=1", "b=2", "c=3"));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test counter", lines.get(0));
        assertEquals("test{a=\"1\",b=\"2\",c=\"3\"} 17.3", lines.get(1));
    }

    @Test
    void sameCounterTwice() {
        var lines = export(
            counter("test", 17.3, "a=1"),
            counter("test", 8.6, "a=2")
        );
        assertEquals(3, lines.size());
        assertEquals("# TYPE test counter", lines.get(0));
        assertEquals("test{a=\"1\"} 17.3", lines.get(1));
        assertEquals("test{a=\"2\"} 8.6", lines.get(2));
    }

    @Test
    void twoDifferentCounters() {
        var lines = export(
            counter("test-1", 17.3, "a=1"),
            counter("test-2", 8.6, "a=2")
        );
        assertEquals(4, lines.size());
        assertEquals("# TYPE test-1 counter", lines.get(0));
        assertEquals("test-1{a=\"1\"} 17.3", lines.get(1));
        assertEquals("# TYPE test-2 counter", lines.get(2));
        assertEquals("test-2{a=\"2\"} 8.6", lines.get(3));
    }

    @Test
    void gauge() {
        var lines = export(gauge("test", 17.3));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test gauge", lines.get(0));
        assertEquals("test 17.3", lines.get(1));
    }

    @Test
    void gaugeWithOneLabel() {
        var lines = export(gauge("test", 17.3, "a=1"));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test gauge", lines.get(0));
        assertEquals("test{a=\"1\"} 17.3", lines.get(1));
    }

    @Test
    void gaugeWithTwoLabels() {
        var lines = export(gauge("test", 17.3, "a=1", "b=2"));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test gauge", lines.get(0));
        assertEquals("test{a=\"1\",b=\"2\"} 17.3", lines.get(1));
    }

    @Test
    void gaugeWithThreeLabels() {
        var lines = export(gauge("test", 17.3, "a=1", "b=2", "c=3"));
        assertEquals(2, lines.size());
        assertEquals("# TYPE test gauge", lines.get(0));
        assertEquals("test{a=\"1\",b=\"2\",c=\"3\"} 17.3", lines.get(1));
    }

    @Test
    void sameGaugeTwice() {
        var lines = export(
            gauge("test", 17.3, "a=1"),
            gauge("test", 8.6, "a=2")
        );
        assertEquals(3, lines.size());
        assertEquals("# TYPE test gauge", lines.get(0));
        assertEquals("test{a=\"1\"} 17.3", lines.get(1));
        assertEquals("test{a=\"2\"} 8.6", lines.get(2));
    }

    @Test
    void twoDifferentGauges() {
        var lines = export(
            gauge("test-1", 17.3, "a=1"),
            gauge("test-2", 8.6, "a=2")
        );
        assertEquals(4, lines.size());
        assertEquals("# TYPE test-1 gauge", lines.get(0));
        assertEquals("test-1{a=\"1\"} 17.3", lines.get(1));
        assertEquals("# TYPE test-2 gauge", lines.get(2));
        assertEquals("test-2{a=\"2\"} 8.6", lines.get(3));
    }
}
