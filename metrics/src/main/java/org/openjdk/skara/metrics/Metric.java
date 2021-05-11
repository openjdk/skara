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

import java.util.List;

public final class Metric {
    public final static class Label {
        private String name;
        private String value;

        public Label(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String name() {
            return name;
        }

        public String value() {
            return value;
        }
    }

    public enum Type {
        COUNTER,
        GAUGE,
        HISTOGRAM,
        SUMMARY;

        @Override
        public String toString() {
            switch (this) {
                case COUNTER:
                    return "counter";
                case GAUGE:
                    return "gauge";
                case HISTOGRAM:
                    return "histogram";
                case SUMMARY:
                    return "summary";
                default:
                    throw new IllegalStateException("Unexpected type");
            }
        }
    }

    private final Type type;
    private final String name;
    private final List<Label> labels;
    private final double value;

    public Metric(Type type, String name, List<Label> labels, double value) {
        this.type = type;
        this.name = name;
        this.labels = labels;
        this.value = value;
    }

    public Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    public List<Label> labels() {
        return labels;
    }

    public double value() {
        return value;
    }
}
