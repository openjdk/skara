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

import java.io.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Execution implements AutoCloseable {

    private final List<ProcessBuilder> processBuilders;
    private final Process.OutputOption outputOption;
    private final Duration timeout;

    private final Logger log = Logger.getLogger("org.openjdk.skara.process");

    private String cmd;
    private int status = 0;
    private File stdoutFile;
    private List<File> stderrFiles;

    private Instant start;
    private boolean finished;
    private Result result;
    private Throwable exception;
    private List<java.lang.Process> processes;

    public static class CheckedResult {

        protected final int status;
        private final String command;
        private final List<String> stdout;
        private final List<String> stderr;
        private final Throwable exception;

        CheckedResult(String command, List<String> stdout, List<String> stderr, int status, Throwable exception) {
            this.status = status;
            this.command = command;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exception = exception;
        }

        public List<String> stdout() {
            return stdout;
        }

        public List<String> stderr() {
            return stderr;
        }

        public Optional<Throwable> exception() {
            return Optional.ofNullable(exception);
        }

        @Override
        public String toString() {
            var lines = new ArrayList<String>();
            lines.add("'" + command + "' exited with status: " + status);

            lines.add("[stdout]");
            for (var line : stdout()) {
                lines.add("> " + line);
            }
            lines.add("[stderr]");
            for (var line : stderr()) {
                lines.add("> " + line);
            }

            return String.join("\n", lines);
        }
    }

    public static class Result extends CheckedResult {


        Result(String command, List<String> stdout, List<String> stderr, int status, Throwable exception) {
            super(command, stdout, stderr, status, exception);
        }

        public int status() {
            return status;
        }
    }

    private ProcessBuilder getLastProcessBuilder() {
        return processBuilders.get(processBuilders.size() - 1);
    }

    private void prepareRedirects() throws IOException {

        // For piped execution, only the last process can generated output on stdout
        if (outputOption == Process.OutputOption.CAPTURE) {
            stdoutFile = File.createTempFile("stdout", ".txt");
            getLastProcessBuilder().redirectOutput(stdoutFile);
        } else if (outputOption == Process.OutputOption.INHERIT) {
            getLastProcessBuilder().redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else {
            getLastProcessBuilder().redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }

        // But every process can write to stderr
        stderrFiles = new LinkedList<>();
        for (var processBuilder : processBuilders) {
            if (outputOption == Process.OutputOption.CAPTURE) {
                var stderrFile = File.createTempFile("stderr", ".txt");
                stderrFiles.add(stderrFile);
                processBuilder.redirectError(stderrFile);
            } else if (outputOption == Process.OutputOption.INHERIT) {
                processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
            } else {
                processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
        }
    }

    private void startProcessPipe() throws IOException {
        cmd = processBuilders.stream()
                             .map(p -> String.join(" ", p.command()))
                             .collect(Collectors.joining(" | "));
        log.fine("Executing pipeline '" + cmd + "'");

        prepareRedirects();
        start = Instant.now();

        processes = ProcessBuilder.startPipeline(processBuilders);
    }

    private void waitForProcessPipe() throws IOException, InterruptedException {
        var remainingTimeout = Duration.from(timeout);
        for (var process : processes) {
            var terminated = false;
            try {
                terminated = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (terminated) {
                    var processStatus = process.exitValue();
                    if (processStatus != 0) {
                        // Set the final status to the rightmost command to exit with a non-zero status,
                        // similar to pipefail in shells
                        status = processStatus;
                    }
                }
            } catch (InterruptedException e) {
                status = -1;
                break;
            }

            if (!terminated) {
                log.warning("Command '" + cmd + "' didn't finish in " + timeout + ", attempting to terminate...");
                try {
                    process.destroyForcibly().waitFor();
                } catch (InterruptedException e) {
                    log.warning("Failed to terminate command");
                    throw new RuntimeException("Failed to terminate timeouted command '" + cmd + "'");
                }
                throw new InterruptedException("Command '" + cmd + "' didn't finish in " + timeout + ", terminated");
            }
            remainingTimeout = remainingTimeout.minus(Duration.between(start, Instant.now()));
            start = Instant.now();
        }
    }

    private void startProcess() throws IOException {
        cmd = String.join(" ", getLastProcessBuilder().command());
        log.fine("Executing '" + cmd + "'");

        prepareRedirects();
        start = Instant.now();

        processes = new LinkedList<>();
        processes.add(getLastProcessBuilder().start());
    }

    private void waitForProcess() throws IOException, InterruptedException {
        var process = processes.get(0);
        var terminated = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!terminated) {
            log.warning("Command '" + cmd + "' didn't finish in " + timeout + ", attempting to terminate...");
            process.destroyForcibly().waitFor();
            throw new InterruptedException("Command '" + cmd + "' didn't finish in " + timeout + ", terminated");
        }
        status = process.exitValue();
    }

    private List<String> content(File f) {
        var p = f.toPath();
        if (Files.exists(p)) {
            try {
                return Files.readAllLines(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ArrayList<String>();
    }

    private Result createResult() {
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();

        if (outputOption == Process.OutputOption.CAPTURE) {
            stdout = content(stdoutFile);
            if (!stdoutFile.delete()) {
                log.warning("Failed to delete stdout file buffer: " + stdoutFile.toString());
            }

            stderr = new ArrayList<String>();
            for (var stderrFile : stderrFiles) {
                stderr.addAll(content(stderrFile));
                if (!stderrFile.delete()) {
                    log.warning("Failed to delete stderr file buffer: " + stderrFile.toString());
                }
            }

        }


        var command = processBuilders.stream()
                                     .map(pb -> String.join(" ", pb.command()))
                                     .reduce("", (res, cmd) -> res.isEmpty() ? cmd : res + " | " + cmd);
        return new Result(command, stdout, stderr, status, exception);
    }

    Execution(List<ProcessBuilder> processBuilders, Process.OutputOption outputOption, Duration timeout) {
        this.processBuilders = processBuilders;
        this.outputOption = outputOption;
        this.timeout = timeout;

        finished = false;

        try {
            if (processBuilders.size() == 1) {
                startProcess();
            } else {
                startProcessPipe();
            }
        } catch (IOException e) {
            log.throwing("Process", "execute", e);
            finished = true;
            exception = e;
            status = -1;
            result = createResult();
        }
    }

    public Result await() {
        synchronized (this) {
            if (!finished) {
                try {
                    if (processBuilders.size() == 1) {
                        waitForProcess();
                    } else {
                        waitForProcessPipe();
                    }
                } catch (IOException | InterruptedException e) {
                    status = -1;
                    exception = e;
                }

                finished = true;
                result = createResult();
            }
        }

        return result;
    }

    public CheckedResult check() {
        var ret = await();
        if (status != 0) {
            if (exception != null) {
                throw new RuntimeException("Exit status from '" + cmd + "': " + status, exception);
            } else {
                throw new RuntimeException("Exit status from '" + cmd + "': " + status);
            }
        }
        return ret;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!finished) {
                // FIXME: stop processes
                finished = true;
                status = -1;
                result = createResult();
            }
        }
    }
}
