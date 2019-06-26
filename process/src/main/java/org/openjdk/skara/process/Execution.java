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

public class Execution implements AutoCloseable {

    private final ProcessBuilder processBuilder;
    private final Process.OutputOption outputOption;
    private final Duration timeout;

    private final Logger log = Logger.getLogger("org.openjdk.skara.process");

    private String cmd;
    private int status = 0;
    private File stdoutFile;
    private File stderrFile;

    private boolean finished;
    private Result result;
    private Throwable exception;
    private java.lang.Process process;

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

    private void prepareRedirects() throws IOException {

        if (outputOption == Process.OutputOption.CAPTURE) {
            stdoutFile = File.createTempFile("stdout", ".txt");
            processBuilder.redirectOutput(stdoutFile);
        } else if (outputOption == Process.OutputOption.INHERIT) {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }

        if (outputOption == Process.OutputOption.CAPTURE) {
            stderrFile = File.createTempFile("stderr", ".txt");
            processBuilder.redirectError(stderrFile);
        } else if (outputOption == Process.OutputOption.INHERIT) {
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        } else {
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }

    }

    private void startProcess() throws IOException {
        cmd = String.join(" ", processBuilder.command());
        log.fine("Executing '" + cmd + "'");

        prepareRedirects();

        process = processBuilder.start();
    }

    private void waitForProcess() throws IOException, InterruptedException {
        var terminated = this.process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!terminated) {
            log.warning("Command '" + cmd + "' didn't finish in " + timeout + ", attempting to terminate...");
            this.process.destroyForcibly().waitFor();
            throw new InterruptedException("Command '" + cmd + "' didn't finish in " + timeout + ", terminated");
        }
        status = this.process.exitValue();
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

            stderr = content(stderrFile);
            if (!stderrFile.delete()) {
                log.warning("Failed to delete stderr file buffer: " + stderrFile.toString());
            }
        }

        return new Result(cmd, stdout, stderr, status, exception);
    }

    Execution(ProcessBuilder processBuilder, Process.OutputOption outputOption, Duration timeout) {
        this.processBuilder = processBuilder;
        this.outputOption = outputOption;
        this.timeout = timeout;

        finished = false;

        try {
            startProcess();
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
                    waitForProcess();
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
                // FIXME: stop process
                finished = true;
                status = -1;
                result = createResult();
            }
        }
    }
}
