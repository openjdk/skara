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
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SubCommandSwitch implements Command {

    private static final SubCommandEntry errorCommand = new SubCommandEntry("", "",
            args -> System.out.println("error: unknown subcommand: " + args[0]));

    private final String programName;
    private final String defaultCommand;
    private final Map<String, SubCommandEntry> subCommands;

    private SubCommandSwitch(String programName, String defaultCommand, Map<String, SubCommandEntry> subCommands) {
        this.programName = programName;
        this.subCommands = subCommands;
        this.subCommands.put("help", helpCommand());
        this.defaultCommand = defaultCommand;
    }

    private SubCommandEntry helpCommand() {
        return new SubCommandEntry("help", "print a help message", args -> showUsage());
    }

    public static Builder builder(String programName) {
        return new Builder(programName);
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length != 0) {
            SubCommandEntry p = subCommands.get(args[0]);
            if (p != null) {
                String[] forwardedArgs = Arrays.copyOfRange(args, 1, args.length);
                p.command.execute(forwardedArgs);
            } else {
                subCommands.getOrDefault(defaultCommand, errorCommand).command.execute(args);
            }
        } else {
            showUsage();
        }
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
            ps.println(String.format("  %-" + spacing + "s%s", subCommand.name, subCommand.description));
        }
    }

    private static class SubCommandEntry {
        private final String name;
        private final String description;
        private final Command command;

        public SubCommandEntry(String name, String description, Command command) {
            this.name = name;
            this.description = description;
            this.command = command;
        }
    }

    public static class Builder {
        private final String programName;

        private String defaultCommand;
        private final Map<String,  SubCommandEntry> subCommands = new HashMap<>();

        private Builder(String programName) {
            this.programName = programName;
        }

        public Builder defaultCommand(String command, String description, Command action) {
            subCommand(command, description, action);
            this.defaultCommand = command;
            return this;
        }

        public Builder subCommand(String command, String description, Command action) {
            subCommands.put(command, new SubCommandEntry(command, description, action));
            return this;
        }

        public SubCommandSwitch build() {
            return new SubCommandSwitch(programName, defaultCommand, subCommands);
        }
    }
}
