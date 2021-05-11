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

import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.ConcurrentHashMap;

public final class Gauge implements Collector {
    public final static class Builder {
        public final static class WithOneLabel {
            private final String name;
            private final String label;

            WithOneLabel(String name, String label) {
                this.name = name;
                this.label = label;
            }

            public Gauge.WithOneLabel register() {
                return register(CollectorRegistry.defaultRegistry());
            }

            public Gauge.WithOneLabel register(CollectorRegistry registry) {
                var gauge = new Gauge.WithOneLabel(name, label);
                registry.register(gauge);
                return gauge;
            }
        }

        public final static class WithTwoLabels {
            private final String name;
            private final String label1;
            private final String label2;

            WithTwoLabels(String name, String label1, String label2) {
                this.name = name;
                this.label1 = label1;
                this.label2 = label2;
            }

            public Gauge.WithTwoLabels register() {
                return register(CollectorRegistry.defaultRegistry());
            }

            public Gauge.WithTwoLabels register(CollectorRegistry registry) {
                var gauge = new Gauge.WithTwoLabels(name, label1, label2);
                registry.register(gauge);
                return gauge;
            }
        }

        public final static class WithThreeLabels {
            private final String name;
            private final String label1;
            private final String label2;
            private final String label3;

            WithThreeLabels(String name, String label1, String label2, String label3) {
                this.name = name;
                this.label1 = label1;
                this.label2 = label2;
                this.label3 = label3;
            }

            public Gauge.WithThreeLabels register() {
                return register(CollectorRegistry.defaultRegistry());
            }

            public Gauge.WithThreeLabels register(CollectorRegistry registry) {
                var gauge = new Gauge.WithThreeLabels(name, label1, label2, label3);
                registry.register(gauge);
                return gauge;
            }
        }

        private final String name;

        Builder(String name) {
            this.name = name;
        }

        public Gauge register() {
            return register(CollectorRegistry.defaultRegistry());
        }

        public Gauge register(CollectorRegistry registry) {
            var gauge = new Gauge(name);
            registry.register(gauge);
            return gauge;
        }

        public Builder.WithOneLabel labels(String label) {
            return new Builder.WithOneLabel(name, label);
        }

        public Builder.WithTwoLabels labels(String label1, String label2) {
            return new Builder.WithTwoLabels(name, label1, label2);
        }

        public Builder.WithThreeLabels labels(String label1, String label2, String label3) {
            return new Builder.WithThreeLabels(name, label1, label2, label3);
        }
    }

    public static final class Adjuster {
        private final DoubleAdder adder;
        private final Consumer<Double> resetter;

        Adjuster(DoubleAdder adder, Consumer<Double> resetter) {
            this.adder = adder;
            this.resetter = resetter;
        }

        public void inc() {
            inc(1);
        }

        public void inc(double d) {
            adder.add(d);
        }

        public void dec() {
            inc(-1);
        }

        public void dec(double d) {
            adder.add(0 - d);
        }

        public void set(double d) {
            resetter.accept(d);
        }

        public void setToCurrentTime() {
            set(ZonedDateTime.now().toInstant().toEpochMilli() / 1000.0);
        }
    }

    private final String name;
    private volatile DoubleAdder value;

    Gauge(String name) {
        this.name = name;
        this.value = new DoubleAdder();
    }

    public static Gauge.Builder name(String name) {
        return new Gauge.Builder(name);
    }

    public void inc() {
        inc(1);
    }

    public void inc(double d) {
        value.add(d);
    }

    public void dec() {
        inc(-1);
    }

    public void dec(double d) {
        value.add(0 - d);
    }

    public void set(double d) {
        var newAdder = new DoubleAdder();
        newAdder.add(d);
        value = newAdder;
    }

    public void setToCurrentTime() {
        set(ZonedDateTime.now().toInstant().toEpochMilli() / 1000.0);
    }

    @Override
    public List<Metric> collect() {
        return List.of(new Metric(Metric.Type.GAUGE, name, List.of(), value.sum()));
    }

    public static final class WithOneLabel implements Collector {
        private final String name;
        private final String label;
        private final ConcurrentHashMap<String, DoubleAdder> value;

        public WithOneLabel(String name, String label) {
            this.name = name;
            this.label = label;
            this.value = new ConcurrentHashMap<String, DoubleAdder>();
        }

        public Adjuster labels(String labelValue) {
            var adder = new DoubleAdder();
            var existing = value.putIfAbsent(labelValue, adder);
            if (existing == null) {
                existing = adder;
            }
            return new Adjuster(existing, (v) -> {
                var newAdder = new DoubleAdder();
                newAdder.add(v);
                value.put(labelValue, newAdder);
            });
        }

        @Override
        public List<Metric> collect() {
            var metrics = new ArrayList<Metric>();
            for (var key : value.keySet()) {
                var l = new Metric.Label(label, key);
                var d = value.get(key);
                metrics.add(new Metric(Metric.Type.GAUGE, name, List.of(l), d.sum()));
            }
            return metrics;
        }
    }

    public static final class WithTwoLabels implements Collector {
        private final String name;
        private final String label1;
        private final String label2;
        private final ConcurrentHashMap<List<String>, DoubleAdder> value;

        public WithTwoLabels(String name, String label1, String label2) {
            this.name = name;
            this.label1 = label1;
            this.label2 = label2;
            this.value = new ConcurrentHashMap<>();
        }

        public Adjuster labels(String labelValue1, String labelValue2) {
            var adder = new DoubleAdder();
            var key = List.of(labelValue1, labelValue2);
            var existing = value.putIfAbsent(key, adder);
            if (existing == null) {
                existing = adder;
            }
            return new Adjuster(existing, (v) -> {
                var newAdder = new DoubleAdder();
                newAdder.add(v);
                value.put(key, newAdder);
            });
        }

        @Override
        public List<Metric> collect() {
            var metrics = new ArrayList<Metric>();
            for (var values : value.keySet()) {
                var labels =
                    List.of(new Metric.Label(label1, values.get(0)),
                            new Metric.Label(label2, values.get(1)));
                var d = value.get(values);
                metrics.add(new Metric(Metric.Type.GAUGE, name, labels, d.sum()));
            }
            return metrics;
        }
    }

    public static final class WithThreeLabels implements Collector {
        private final String name;
        private final String label1;
        private final String label2;
        private final String label3;
        private final ConcurrentHashMap<List<String>, DoubleAdder> value;

        public WithThreeLabels(String name, String label1, String label2, String label3) {
            this.name = name;
            this.label1 = label1;
            this.label2 = label2;
            this.label3 = label3;
            this.value = new ConcurrentHashMap<>();
        }

        public Adjuster labels(String labelValue1, String labelValue2, String labelValue3) {
            var adder = new DoubleAdder();
            var key = List.of(labelValue1, labelValue2, labelValue3);
            var existing = value.putIfAbsent(key, adder);
            if (existing == null) {
                existing = adder;
            }
            return new Adjuster(existing, (v) -> {
                var newAdder = new DoubleAdder();
                newAdder.add(v);
                value.put(key, newAdder);
            });
        }

        @Override
        public List<Metric> collect() {
            var metrics = new ArrayList<Metric>();
            for (var values : value.keySet()) {
                var labels =
                    List.of(new Metric.Label(label1, values.get(0)),
                            new Metric.Label(label2, values.get(1)),
                            new Metric.Label(label3, values.get(2)));
                var d = value.get(values);
                metrics.add(new Metric(Metric.Type.GAUGE, name, labels, d.sum()));
            }
            return metrics;
        }
    }
}
