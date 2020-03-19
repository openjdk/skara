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
package org.openjdk.skara.cli.pr;

import org.openjdk.skara.args.*;
import org.openjdk.skara.version.Version;
import org.openjdk.skara.cli.Logging;

import java.util.*;
import java.util.logging.Level;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GitPrHelp {
    private static final class Pair<T1, T2> {
        T1 e1;
        T2 e2;

        Pair(T1 e1, T2 e2) {
            this.e1 = e1;
            this.e2 = e2;
        }

        static <T3, T4> Pair<T3, T4> of(T3 e1, T4 e2) {
            return new Pair<T3, T4>(e1, e2);
        }

        T1 first() {
            return e1;
        }

        T2 second() {
            return e2;
        }
    }

    private static final Map<String, Pair<List<Input>, List<Flag>>> commands = new HashMap<>();

    static {
        commands.put("list", Pair.of(GitPrList.inputs, GitPrList.flags));
        commands.put("fetch", Pair.of(GitPrFetch.inputs, GitPrFetch.flags));
        commands.put("show", Pair.of(GitPrShow.inputs, GitPrShow.flags));
        commands.put("checkout", Pair.of(GitPrCheckout.inputs, GitPrCheckout.flags));
        commands.put("apply", Pair.of(GitPrApply.inputs, GitPrApply.flags));
        commands.put("integrate", Pair.of(GitPrIntegrate.inputs, GitPrIntegrate.flags));
        commands.put("approve", Pair.of(GitPrApprove.inputs, GitPrApprove.flags));
        commands.put("create", Pair.of(GitPrCreate.inputs, GitPrCreate.flags));
        commands.put("close", Pair.of(GitPrClose.inputs, GitPrClose.flags));
        commands.put("set", Pair.of(GitPrSet.inputs, GitPrSet.flags));
        commands.put("sponsor", Pair.of(GitPrSponsor.inputs, GitPrSponsor.flags));
        commands.put("test", Pair.of(GitPrTest.inputs, GitPrTest.flags));
        commands.put("info", Pair.of(GitPrInfo.inputs, GitPrInfo.flags));
    }

    private static String describe(List<Input> inputs) {
        return inputs.stream().map(Input::toString).collect(Collectors.joining(" "));
    }

    private static<T> TreeSet<T> sorted(Set<T> s) {
        return new TreeSet<T>(s);
    }

    private static void showHelpFor(String command, int indentation) {
        var inputs = commands.get(command).first();
        var flags = commands.get(command).second();

        System.out.println(" ".repeat(indentation) + "Usage: git pr " + command + " " + describe(inputs));
        System.out.println(" ".repeat(indentation) + "Flags:");
        ArgumentParser.showFlags(System.out, flags, " ".repeat(indentation + 2));
    }

    public static void main(String[] args) {
        var flags = List.of(
            Switch.shortcut("h")
                  .fullname("help")
                  .helptext("Show help")
                  .optional(),
            Switch.shortcut("")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("debug")
                  .helptext("Turn on debugging output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional()
        );

        var inputs = List.of(
            Input.position(0)
                 .describe("COMMAND")
                 .singular()
                 .optional()
        );

        var parser = new ArgumentParser("git-pr", flags, inputs);
        var arguments = parser.parse(args);
        if (arguments.contains("version")) {
            System.out.println("git-pr version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }
        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        if (arguments.at(0).isPresent()) {
            var command = arguments.at(0).asString();
            if (commands.keySet().contains(command)) {
               showHelpFor(command, 0);
               System.exit(0);
            } else {
                System.err.println("error: unknown sub-command: " + command);
                System.err.println("");
                System.err.println("Available sub-commands are:");
                for (var subcommand : sorted(commands.keySet())) {
                    System.err.println("- " + subcommand);
                }
                System.exit(1);
            }
        }

        System.out.println("git-pr is used for interacting with pull requeqsts from a command line.");
        System.out.println("The following commands are available:");
        for (var command : sorted(commands.keySet())) {
            System.out.println("- " + command);
            showHelpFor(command, 2);
        }
    }
}
