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

import java.util.*;

public class PrometheusExporter implements Exporter {
    @Override
    public String export(List<Metric> metrics) {
        var typed = new HashSet<String>();
        var sb = new StringBuilder();
        for (var metric : metrics) {
            if (!typed.contains(metric.name())) {
                sb.append("# TYPE ");
                sb.append(metric.name());
                sb.append(" ");
                sb.append(metric.type().toString());
                sb.append("\n");

                typed.add(metric.name());
            }
            sb.append(metric.name());
            var labels = metric.labels();
            if (!labels.isEmpty()) {
                sb.append("{");
                for (var i = 0; i < labels.size(); i++) {
                    var label = labels.get(i);
                    sb.append(label.name());
                    sb.append("=\"");
                    sb.append(label.value());
                    sb.append("\"");
                    if (i != labels.size() - 1) {
                        sb.append(",");
                    }
                }
                sb.append("}");
            }
            sb.append(" ");
            sb.append(Double.toString(metric.value()));
            sb.append("\n");
        }
        return sb.toString();
    }
}
