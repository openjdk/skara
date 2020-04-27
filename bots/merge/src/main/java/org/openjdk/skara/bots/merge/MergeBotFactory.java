/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.merge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.vcs.Branch;

import java.io.*;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public class MergeBotFactory implements BotFactory {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    @Override
    public String name() {
        return "merge";
    }

    private static MergeBot.Spec.Frequency.Interval toInterval(String s) {
        switch (s.toLowerCase()) {
            case "hourly":
                return MergeBot.Spec.Frequency.Interval.HOURLY;
            case "daily":
                return MergeBot.Spec.Frequency.Interval.DAILY;
            case "weekly":
                return MergeBot.Spec.Frequency.Interval.WEEKLY;
            case "monthly":
                return MergeBot.Spec.Frequency.Interval.MONTHLY;
            case "yearly":
                return MergeBot.Spec.Frequency.Interval.YEARLY;
            default:
                throw new IllegalArgumentException("Unknown interval: " + s);
        }
    }

    private static DayOfWeek toWeekday(String s) {
        switch (s.toLowerCase()) {
            case "monday":
                return DayOfWeek.MONDAY;
            case "tuesday":
                return DayOfWeek.TUESDAY;
            case "wednesday":
                return DayOfWeek.WEDNESDAY;
            case "thursday":
                return DayOfWeek.THURSDAY;
            case "friday":
                return DayOfWeek.FRIDAY;
            case "saturday":
                return DayOfWeek.SATURDAY;
            case "sunday":
                return DayOfWeek.SUNDAY;
            default:
                throw new IllegalArgumentException("Unknown weekday: " + s);
        }
    }

    private static Month toMonth(String s) {
        switch (s.toLowerCase()) {
            case "january":
                return Month.JANUARY;
            case "february":
                return Month.FEBRUARY;
            case "march":
                return Month.MARCH;
            case "april":
                return Month.APRIL;
            case "may":
                return Month.MAY;
            case "june":
                return Month.JUNE;
            case "july":
                return Month.JULY;
            case "august":
                return Month.AUGUST;
            case "september":
                return Month.SEPTEMBER;
            case "october":
                return Month.OCTOBER;
            case "november":
                return Month.NOVEMBER;
            case "december":
                return Month.DECEMBER;
            default:
                throw new IllegalArgumentException("Unknown month: " + s);
        }
    }

    private static int toDay(int i) {
        if (i < 0 || i > 30) {
            throw new IllegalArgumentException("Unknown day: " + i);
        }
        return i;
    }

    private static int toHour(int i) {
        if (i < 0 || i > 23) {
            throw new IllegalArgumentException("Unknown hour: " + i);
        }
        return i;
    }

    private static int toMinute(int i) {
        if (i < 0 || i > 59) {
            throw new IllegalArgumentException("Unknown minute: " + i);
        }
        return i;
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var storage = configuration.storageFolder();
        try {
            Files.createDirectories(storage);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var specific = configuration.specific();

        var bots = new ArrayList<Bot>();
        for (var repo : specific.get("repositories").asArray()) {
            var targetRepo = configuration.repository(repo.get("target").asString());
            var forkRepo = configuration.repository(repo.get("fork").asString());

            var specs = new ArrayList<MergeBot.Spec>();
            for (var spec : repo.get("spec").asArray()) {
                var from = spec.get("from").asString().split(":");
                var fromRepo = configuration.repository(from[0]);
                var fromBranch = new Branch(from[1]);
                var toBranch = new Branch(spec.get("to").asString());

                MergeBot.Spec.Frequency frequency = null;
                if (spec.contains("frequency")) {
                    var freq = spec.get("frequency").asObject();
                    var interval = toInterval(freq.get("interval").asString());
                    if (interval.isHourly()) {
                        var minute = toMinute(freq.get("minute").asInt());
                        frequency = MergeBot.Spec.Frequency.hourly(minute);
                    } else if (interval.isDaily()) {
                        var hour = toHour(freq.get("hour").asInt());
                        frequency = MergeBot.Spec.Frequency.daily(hour);
                    } else if (interval.isWeekly()) {
                        var weekday = toWeekday(freq.get("weekday").asString());
                        var hour = toHour(freq.get("hour").asInt());
                        frequency = MergeBot.Spec.Frequency.weekly(weekday, hour);
                    } else if (interval.isMonthly()) {
                        var day = toDay(freq.get("day").asInt());
                        var hour = toHour(freq.get("hour").asInt());
                        frequency = MergeBot.Spec.Frequency.monthly(day, hour);
                    } else if (interval.isYearly()) {
                        var month = toMonth(freq.get("month").asString());
                        var day = toDay(freq.get("day").asInt());
                        var hour = toHour(freq.get("hour").asInt());
                        frequency = MergeBot.Spec.Frequency.yearly(month, day, hour);
                    } else {
                        throw new IllegalStateException("Unexpected interval: " + interval);
                    }
                }

                var name = spec.getOrDefault("name", JSON.of()).asString();
                var dependencies = spec.getOrDefault("dependencies", JSON.array())
                                       .stream()
                                       .map(e -> e.asString())
                                       .collect(Collectors.toList());
                var prerequisites = spec.getOrDefault("prerequisites", JSON.array())
                                        .stream()
                                        .map(e -> e.asString())
                                        .map(configuration::repository)
                                        .collect(Collectors.toList());

                specs.add(new MergeBot.Spec(fromRepo,
                                            fromBranch,
                                            toBranch,
                                            frequency,
                                            name,
                                            dependencies,
                                            prerequisites));
            }

            bots.add(new MergeBot(storage, targetRepo, forkRepo, specs));
        }
        return bots;
    }
}
