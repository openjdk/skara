/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.testinfo;

import org.openjdk.skara.forge.*;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TestResults {
    private static String platformFromName(String checkName) {
        var checkFlavorStart = checkName.indexOf("(");
        if (checkFlavorStart > 0) {
            return checkName.substring(0, checkFlavorStart - 1).strip();
        } else {
            return checkName.strip();
        }
    }

    private static String flavorFromName(String checkName) {
        var checkFlavorStart = checkName.indexOf("(");
        var checkFlavorEnd = checkName.lastIndexOf(")");
        if (checkFlavorStart > 0 && checkFlavorEnd > checkFlavorStart) {
            var flavor = checkName.substring(checkFlavorStart + 1, checkFlavorEnd).strip().toLowerCase();
            for (int i = 1; i < 10; ++i) {
                if (flavor.contains("tier" + i)) {
                    return "Test (tier" + i + ")";
                }
            }
            if (flavor.contains("build")) {
                return "Build";
            }
        }
        // Fallback value
        return "Build / test";
    }

    private static boolean ignoredCheck(String checkName) {
        var lcName = checkName.toLowerCase();
        return lcName.contains("jcheck") || lcName.contains("prerequisites") || lcName.contains("post-process");
    }

    // Retain only the latest when there are multiple checks with the same name
    private static Collection<Check> latestChecks(List<Check> checks) {
        var latestChecks = checks.stream()
                                 .filter(check -> !ignoredCheck(check.name()))
                                 .filter(check -> check.status() != CheckStatus.CANCELLED)
                                 .sorted(Comparator.comparing(Check::startedAt, ZonedDateTime::compareTo))
                                 .collect(Collectors.toMap(Check::name, Function.identity(), (a, b) -> b, LinkedHashMap::new));
        return latestChecks.values();
    }

    static List<Check> summarize(List<Check> checks) {
        var latestChecks = latestChecks(checks);
        if (latestChecks.isEmpty()) {
            return List.of();
        }

        var hash = latestChecks.stream().findAny().orElseThrow().hash();
        var platforms = latestChecks.stream()
                                    .map(check -> platformFromName(check.name()))
                                    .collect(Collectors.toCollection(TreeSet::new));
        var flavors = latestChecks.stream()
                                  .map(check -> flavorFromName(check.name()))
                                  .collect(Collectors.toCollection(TreeSet::new));
        if (platforms.isEmpty() || flavors.isEmpty()) {
            return List.of();
        }

        var platformFlavors = latestChecks.stream()
                                          .collect(Collectors.groupingBy(check -> platformFromName(check.name()))).entrySet().stream()
                                          .collect(Collectors.toMap(Map.Entry::getKey,
                                                                    entry -> entry.getValue().stream()
                                                                                  .collect(Collectors.groupingBy(check -> flavorFromName(check.name())))));

        var ret = new ArrayList<Check>();
        for (var flavor : flavors) {
            for (var platform : platforms) {
                var platformChecks = platformFlavors.get(platform);
                var flavorChecks = platformChecks.get(flavor);
                if (flavorChecks != null) {
                    int failureCount = 0;
                    int pendingCount = 0;
                    int successCount = 0;
                    var checkDetails = new ArrayList<String>();
                    String checkIcon = "";
                    for (var check : flavorChecks) {
                        switch (check.status()) {
                            case IN_PROGRESS:
                                checkIcon = "⏳ ";
                                pendingCount++;
                                break;
                            case FAILURE:
                                checkIcon = "❌ ";
                                failureCount++;
                                break;
                            case SUCCESS:
                                checkIcon = "✔️ ";
                                successCount++;
                                break;
                        }
                        var checkTitle = check.details().isPresent() ? "[" + check.name() + "](" + check.details().get() + ")" : check.name();
                        checkDetails.add(checkIcon + checkTitle);
                    }
                    var checkBuilder = CheckBuilder.create(platform + " - " + flavor, hash);
                    checkBuilder.summary(String.join("\n", checkDetails));
                    int total = failureCount + pendingCount + successCount;
                    // Report aggregate counts for successful / still running tests
                    if (pendingCount > 0) {
                        checkBuilder.title(pendingCount + "/" + total + " running");
                        ret.add(checkBuilder.build());
                    } else if (successCount > 0) {
                        checkBuilder.title(successCount + "/" + total + " passed");
                        checkBuilder.complete(true);
                        ret.add(checkBuilder.build());
                    }
                }
            }
        }

        var failedChecks = latestChecks.stream()
                                       .filter(check -> check.status() == CheckStatus.FAILURE)
                                       .sorted(Comparator.comparing(Check::name))
                                       .collect(Collectors.toList());
        for (var check : failedChecks) {
            var checkBuilder = CheckBuilder.create(check.name(), hash);
            checkBuilder.title("Failure");
            checkBuilder.summary("A failing check run was found");
            check.details().ifPresent(checkBuilder::details);
            checkBuilder.complete(false);
            ret.add(checkBuilder.build());
        }

        return ret;
    }

    static Optional<Duration> expiresIn(List<Check> checks) {
        var latestChecks = latestChecks(checks);
        var needRefresh = latestChecks.stream()
                                      .filter(check -> check.status() == CheckStatus.IN_PROGRESS)
                                      .findAny();
        if (needRefresh.isPresent()) {
            return Optional.of(Duration.ofMinutes(2));
        } else {
            return Optional.empty();
        }
    }
}
