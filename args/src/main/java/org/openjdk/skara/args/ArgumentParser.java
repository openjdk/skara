/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.args;

import java.io.PrintStream;
import java.util.*;
import java.util.function.Function;

public class ArgumentParser {
    private final String programName;
    private final List<Flag> flags;
    private final List<Input> inputs;
    private final Map<String, Flag> names = new HashMap<String, Flag>();

    public ArgumentParser(String programName, List<Flag> flags) {
        this(programName, flags, List.of());
    }

    public ArgumentParser(String programName, List<Flag> flags, List<Input> inputs) {
        this.programName = programName;
        this.flags = new ArrayList<Flag>(flags);
        this.inputs = inputs;

        var help = Switch.shortcut("h")
                         .fullname("help")
                         .helptext("Show this help text")
                         .optional();
        this.flags.add(help);

        for (var flag : this.flags) {
            if (!flag.fullname().equals("")) {
                names.put(flag.fullname(), flag);
            }
            if (!flag.shortcut().equals("")) {
                names.put(flag.shortcut(), flag);
            }
        }
    }

    private Flag lookupFlag(String name, boolean isShortcut) {
        if (!names.containsKey(name)) {
            System.err.print("Unexpected option: ");
            System.err.print(isShortcut ? "-" : "--");
            System.err.println(name);
            showUsage();
            System.exit(1);
        }

        return names.get(name);
    }

    private Flag lookupFullname(String name) {
        return lookupFlag(name, false);
    }

    private Flag lookupShortcut(String name) {
        return lookupFlag(name, true);
    }

    private int longest(Function<Flag, String> getName) {
        return flags.stream()
                    .map(getName)
                    .filter(Objects::nonNull)
                    .mapToInt(String::length)
                    .reduce(0, Integer::max);
    }

    private int longestShortcut() {
        return longest(Flag::shortcut);
    }

    private int longestFullname() {
        return longest(f -> f.fullname() + " " + f.description());
    }

    public void showUsage() {
        showUsage(System.out);
    }

    public void showUsage(PrintStream ps) {
        ps.print("usage: ");
        ps.print(programName);
        ps.print(" [options]");
        for (var flag : flags) {
            if (flag.isRequired()) {
                ps.print(" ");
                if (!flag.fullname().equals("")) {
                    ps.print("--");
                    ps.print(flag.fullname());
                    if (!flag.description().equals("")) {
                        ps.print("=");
                        ps.print(flag.description());
                    }
                } else {
                    ps.print("-" + flag.shortcut());
                    if (!flag.description().equals("")) {
                        ps.print(" ");
                        ps.print(flag.description());
                    }
                }
            }
        }
        for (var input : inputs) {
            ps.print(" ");
            ps.print(input.toString());
        }
        ps.println("");

        var shortcutPad = longestShortcut() + 1 + 2; // +1 for '-' and +2 for ', '
        var fullnamePad = longestFullname() + 2 + 2; // +2 for '--' and +2 for '  '

        for (var flag : flags) {
            ps.print("\t");
            var fmt = "%-" + shortcutPad + "s";
            var s = flag.shortcut().equals("") ? " " : "-" + flag.shortcut() + ", ";
            ps.print(String.format(fmt, s));

            fmt = "%-" + fullnamePad + "s";
            var desc = flag.description().equals("") ? "" : " " + flag.description();
            s = flag.fullname().equals("") ? " " : "--" + flag.fullname() + desc + "  ";
            ps.print(String.format(fmt, s));

            if (!flag.helptext().equals("")) {
                ps.print(flag.helptext());
            }

            ps.println("");
        }
    }

    public Arguments parse(String[] args) {
        var seen = new HashSet<Flag>();
        var values = new ArrayList<FlagValue>();
        var positional = new ArrayList<String>();

        var i = 0;
        while (i < args.length) {
            var arg = args[i];

            if (arg.startsWith("--")) {
                if (arg.contains("=")) {
                    var parts = arg.split("=");
                    var name = parts[0].substring(2); // remove leading '--'
                    var value = parts.length == 2 ? parts[1] : null;
                    var flag = lookupFullname(name);
                    values.add(new FlagValue(flag, value));
                    seen.add(flag);
                } else {
                    var name = arg.substring(2);
                    var flag = lookupFullname(name);
                    if (flag.isSwitch()) {
                        values.add(new FlagValue(flag, "true"));
                    } else {
                        if (i < (args.length - 1)) {
                            var value = args[i + 1];
                            values.add(new FlagValue(flag, value));
                            i++;
                        } else {
                            values.add(new FlagValue(flag));
                        }
                    }
                    seen.add(flag);
                }
            } else if (arg.startsWith("-") && !arg.equals("-")) {
                var name = arg.substring(1);
                var flag = lookupShortcut(name);
                if (flag.isSwitch()) {
                    values.add(new FlagValue(flag, "true"));
                } else {
                    if (i < (args.length - 1)) {
                        var value = args[i + 1];
                        values.add(new FlagValue(flag, value));
                        i++;
                    } else {
                        values.add(new FlagValue(flag));
                    }
                }
                seen.add(flag);
            } else {
                int argPos = positional.size();
                if (argPos >= inputs.size()) {
                    // must check if permitted
                    if (inputs.size() == 0) {
                        System.err.println("error: unexpected input: " + arg);
                        showUsage();
                        System.exit(1);
                    }
                    var last = inputs.get(inputs.size() - 1);
                    if ((last.getPosition() + last.getOccurrences()) <= argPos && !last.isTrailing()) {
                        // this input is not permitted
                        System.err.println("error: unexpected input: " + arg);
                        showUsage();
                        System.exit(1);
                    }
                }

                positional.add(arg);
            }
            i++;
        }

        var arguments = new Arguments(values, positional);
        if (arguments.contains("help")) {
            showUsage();
            System.exit(0);
        }

        var errors = new ArrayList<String>();
        for (var flag : flags) {
            if (flag.isRequired() && !seen.contains(flag)) {
                errors.add("error: missing required flag: " + flag.toString());
            }
        }
        for (var input : inputs) {
            if (input.isRequired() && !(positional.size() > input.getPosition())) {
                errors.add("error: missing required input: " + input.toString());
            }
        }

        if (!errors.isEmpty()) {
            for (var error : errors) {
                System.err.println(error);
            }
            showUsage();
            System.exit(1);
        }

        return arguments;
    }
}
