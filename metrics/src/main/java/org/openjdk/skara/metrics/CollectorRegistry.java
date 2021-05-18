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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

public final class CollectorRegistry {
    private static final CollectorRegistry DEFAULT = new CollectorRegistry(true);
    private final ConcurrentLinkedQueue<Collector> collectors = new ConcurrentLinkedQueue<>();
    private final boolean includeHotspotMetrics;

    public CollectorRegistry(boolean includeHotspotMetrics) {
        this.includeHotspotMetrics = includeHotspotMetrics;
    }

    public void register(Collector c) {
        collectors.add(c);
    }

    public void unregister(Collector c) {
        collectors.remove(c);
    }

    private static List<Metric> memoryUsageMetrics(String prefix, List<Metric.Label> labels, MemoryUsage usage) {
        var result = new ArrayList<Metric>();
        var max = usage.getMax();
        if (max != -1) {
            result.add(new Metric(Metric.Type.GAUGE, prefix + "_max", labels, max));
        }
        result.add(new Metric(Metric.Type.GAUGE, prefix + "_used", labels, usage.getUsed()));
        result.add(new Metric(Metric.Type.GAUGE, prefix + "_committed", labels, usage.getCommitted()));
        var init = usage.getInit();
        if (init != -1) {
            result.add(new Metric(Metric.Type.GAUGE, prefix + "_init", labels, init));
        }
        return result;
    }

    private static List<Metric> hotspotMetrics() {
        var result = new ArrayList<Metric>();

        var memoryMXBean = ManagementFactory.getMemoryMXBean();
        var heapUsage = memoryMXBean.getHeapMemoryUsage();
        var heapLabels = List.of(new Metric.Label("type", MemoryType.HEAP.toString()));
        result.addAll(memoryUsageMetrics("hotspot_memory", heapLabels, heapUsage));

        var nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        var nonHeapLabels = List.of(new Metric.Label("type", MemoryType.NON_HEAP.toString()));
        result.addAll(memoryUsageMetrics("hotspot_memory", nonHeapLabels, nonHeapUsage));

        var numThreads = ManagementFactory.getThreadMXBean().getThreadCount();
        result.add(new Metric(Metric.Type.GAUGE, "hotspot_threads", List.of(), numThreads));

        var uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        result.add(new Metric(Metric.Type.COUNTER, "hotspot_uptime", List.of(), uptime));

        for (var gcMXBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            var labels = List.of(new Metric.Label("gc_name", gcMXBean.getName()));

            var gcCount = gcMXBean.getCollectionCount();
            result.add(new Metric(Metric.Type.COUNTER, "hotspot_gc_count", labels, gcCount));

            var gcTime = gcMXBean.getCollectionTime() / 1000.0;
            result.add(new Metric(Metric.Type.COUNTER, "hotspot_gc_time", labels, gcTime));
        }

        for (var memoryPoolMXBean : ManagementFactory.getMemoryPoolMXBeans()) {
            var labels = List.of(new Metric.Label("memory_pool_name", memoryPoolMXBean.getName()),
                                 new Metric.Label("memory_pool_type", memoryPoolMXBean.getType().toString()));
            var usage = memoryPoolMXBean.getUsage();
            result.addAll(memoryUsageMetrics("hotspot_memory_pool", labels, usage));
        }

        var compilationMXBean = ManagementFactory.getCompilationMXBean();
        if (compilationMXBean.isCompilationTimeMonitoringSupported()) {
            var compilationTime = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
            result.add(new Metric(Metric.Type.COUNTER, "hotspot_compilation_time", List.of(), compilationTime));
        }

        var classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        var totalLoadedClasses = classLoadingMXBean.getTotalLoadedClassCount();
        result.add(new Metric(Metric.Type.COUNTER, "hotspot_classes_loaded", List.of(), totalLoadedClasses));
        var totalUnloadedClasses = classLoadingMXBean.getTotalLoadedClassCount();
        result.add(new Metric(Metric.Type.COUNTER, "hotspot_classes_unloaded", List.of(), totalUnloadedClasses));

        return result;
    }

    public List<Metric> scrape() {
        var result = new ArrayList<Metric>();
        for (var collector : collectors) {
            result.addAll(collector.collect());
        }
        if (includeHotspotMetrics) {
            result.addAll(hotspotMetrics());
        }
        return result;
    }

    public static CollectorRegistry defaultRegistry() {
        return DEFAULT;
    }
}
