/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MultiCommandParser {
    private final String programName;
    private final String defaultCommand;
    private final Map<String, Command> subCommands;

    public MultiCommandParser(String programName, List<Command> commands) {
        var defaults = commands.stream().filter(Default.class::isInstance).collect(Collectors.toList());
        if (defaults.size() != 1) {
            throw new IllegalArgumentException("Expecting exactly one default command");
        }
        this.defaultCommand = defaults.get(0).name();

        this.programName = programName;
        this.subCommands = commands.stream()
                                   .collect(Collectors.toMap(
                                           Command::name,
                                           Function.identity()));
        if (!commands.stream().anyMatch(c -> c.name().equals("help"))) {
            this.subCommands.put("help", helpCommand());
        }
    }

    private Command helpCommand() {
        return new Command("help", "print a help message", args -> showUsage());
    }

    public Executable parse(String[] args) {
        if (args.length > 0) {
            var p = subCommands.get(args[0]);
            if (p != null) {
                var forwardedArgs = Arrays.copyOfRange(args, 1, args.length);
                return () -> p.main(forwardedArgs);
            }
        }
        return () -> subCommands.get(defaultCommand).main(args);
    }

    private void showUsage() {
        showUsage(System.out);
    }

    private void showUsage(PrintStream ps) {
        ps.print("usage: ");
        ps.print(programName);
        ps.print(subCommands.keySet().stream().collect(Collectors.joining("|", " <", ">")));
        ps.println(" <input>");

        int spacing = subCommands.keySet().stream().mapToInt(String::length).max().orElse(0);
        spacing += 8; // some room

        for (var subCommand : subCommands.values()) {
            ps.println(String.format("  %-" + spacing + "s%s", subCommand.name(), subCommand.helpText()));
        }
    }
}
