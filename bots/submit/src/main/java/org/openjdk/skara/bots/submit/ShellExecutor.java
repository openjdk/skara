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
package org.openjdk.skara.bots.submit;

import org.openjdk.skara.forge.CheckBuilder;

import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;

public class ShellExecutor implements SubmitExecutor {
    private final List<String> cmd;
    private final String name;
    private final Duration timeout;
    private final Map<String, String> environment;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.submit");

    ShellExecutor(String name, List<String> cmd, Duration timeout, Map<String, String> environment) {
        this.cmd = cmd;
        this.name = name;
        this.timeout = timeout;
        this.environment = environment;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public String checkName() {
        return name;
    }

    private String outputSummary(List<String> lines) {
        var lastLines = lines.subList(Math.max(lines.size() - 11, 0), lines.size());
        return "Last 10 lines of output (" + lines.size() + " total lines):\n\n```\n" +
                String.join("\n", lastLines) + "\n```";
    }

    private String durationSummary(Instant start) {
        var executionTime = Duration.between(start, Instant.now());
        if (executionTime.toSeconds() < 60) {
            return executionTime.toSeconds() + " second" + (executionTime.toSeconds() != 1 ? "s" : "");
        } else if (executionTime.toMinutes() < 60) {
            return executionTime.toMinutes() + " minute" + (executionTime.toMinutes() != 1 ? "s" : "");
        } else {
            return executionTime.toHours() + " hour" + (executionTime.toHours() != 1 ? "s" : "") +
                executionTime.toMinutes() + " minute" + (executionTime.toMinutes() % 60 != 1 ? "s" : "");
        }
    }

    @Override
    public void run(Path prFiles, CheckBuilder checkBuilder, Runnable updateProgress) {
        var lines = new ArrayList<String>();
        var start = Instant.now();
        try {
            checkBuilder.title("Shell command execution starting");
            updateProgress.run();
            var pb = new ProcessBuilder(cmd.toArray(new String[0]))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .directory(prFiles.toFile());
            pb.environment().putAll(environment);
            var process = pb.start();

            var watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeout.toMillis());
                } catch (InterruptedException ignored) {
                }
                process.destroyForcibly();
            });
            watchdog.start();

            try {
                var stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                var line = stdout.readLine();
                while (line != null) {
                    line = line.replaceAll("[^\\p{Print}]", "");
                    log.fine("stdout: " + line);
                    lines.add(line);
                    checkBuilder.title("Shell command execution in progress for " + durationSummary(start));
                    checkBuilder.summary(outputSummary(lines));
                    updateProgress.run();
                    line = stdout.readLine();
                }

                var exitValue = process.waitFor();
                log.fine("exit value: " + exitValue);
                if (exitValue == 0) {
                    checkBuilder.complete(true);
                    checkBuilder.title("Shell command executed successfully in " + durationSummary(start));
                    checkBuilder.summary(null);
                } else {
                    checkBuilder.complete(false);
                    checkBuilder.title("Shell command execution failed after " + durationSummary(start) + " (exit code " + exitValue + ")");
                    if (!lines.isEmpty()) {
                        checkBuilder.summary(outputSummary(lines));
                    }
                }
            } catch (InterruptedException e) {
                checkBuilder.complete(false);
                checkBuilder.title("Shell command execution interrupted after " + durationSummary(start) + ": " + e.getMessage());
                if (!lines.isEmpty()) {
                    checkBuilder.summary(outputSummary(lines));
                }
            } finally {
                watchdog.interrupt();
            }
        } catch (IOException e) {
            checkBuilder.complete(false);
            checkBuilder.title("Failed to execute shell command after " + durationSummary(start) + ": " + e.getMessage());
            if (!lines.isEmpty()) {
                checkBuilder.summary(outputSummary(lines));
            }
        }
    }
}
