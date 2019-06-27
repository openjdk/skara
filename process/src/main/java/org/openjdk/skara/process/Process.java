/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class Process {
    enum OutputOption {
        CAPTURE,
        INHERIT,
        DISCARD
    }

    public static class Description {

        private static class ProcessBuilderSetup {
            final List<String> command;
            final Map<String, String> environment;
            Path workdir;

            ProcessBuilderSetup(String... command) {
                this.command = List.of(command);
                environment = new HashMap<>();
            }
        }

        private final OutputOption outputOption;
        private ProcessBuilderSetup processBuilderSetup;
        private Duration timeout;

        Description(Process.OutputOption outputOption, String... command) {
            this.outputOption = outputOption;
            timeout = Duration.ofHours(6);

            this.processBuilderSetup = new ProcessBuilderSetup(command);
        }

        private ProcessBuilderSetup getCurrentProcessBuilderSetup() {
            return processBuilderSetup;
        }

        public Description environ(String key, String value) {
            getCurrentProcessBuilderSetup().environment.put(key, value);
            return this;
        }

        public Description timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Description workdir(Path workdir) {
            getCurrentProcessBuilderSetup().workdir = workdir;
            return this;
        }

        public Description workdir(String workdir) {
            getCurrentProcessBuilderSetup().workdir = Path.of(workdir);
            return this;
        }

        public Execution execute() {

            var builder = new ProcessBuilder(processBuilderSetup.command.toArray(new String[0]));
            builder.environment().putAll(processBuilderSetup.environment);
            if (processBuilderSetup.workdir != null) {
                builder.directory(processBuilderSetup.workdir.toFile());
            }

            return new Execution(builder, outputOption, timeout);
        }

    }

    /**
     * Construct a process description that can be executed, with the output captured.
     * @param command
     * @return
     */
    public static Description capture(String... command) {
        return new Description(Process.OutputOption.CAPTURE, command);
    }

    /**
     * Construct a process description that can be executed, with the output inherited.
     * @param command
     * @return
     */
    public static Description command(String... command) {
        return new Description(Process.OutputOption.INHERIT, command);
    }

    /**
     * Construct a process description that can be executed, with the output discarded.
     * @param command
     * @return
     */
    public static Description discard(String... command) {
        return new Description(Process.OutputOption.DISCARD, command);
    }
}
