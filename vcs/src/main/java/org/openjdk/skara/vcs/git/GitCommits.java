/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs.git;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.io.*;
import java.util.*;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class GitCommits implements Commits, AutoCloseable {

    private final static Logger log = Logger.getLogger("org.openjdk.skara.vcs.git.GitCommits");

    private static final String COMMIT_DELIMITER = "#@!_-=&";

    private final Path dir;
    private final String range;
    private final List<Hash> from;
    private final List<Hash> notFrom;
    private final boolean reverse;
    private final int num;
    private final String format;

    private final List<Process> processes = new ArrayList<>();
    private final List<List<String>> commands = new ArrayList<>();
    private boolean closed = false;

    public GitCommits(Path dir, String range, boolean reverse, int num) throws IOException {
        this.dir = dir;
        this.range = range;
        this.from = null;
        this.notFrom = null;
        this.reverse = reverse;
        this.num = num;
        this.format = String.join("%n",
                                  COMMIT_DELIMITER,
                                  GitCommitMetadata.FORMAT);

    }

    public GitCommits(Path dir, List<Hash> reachableFrom, List<Hash> unreachableFrom) throws IOException {
        this.dir = dir;
        this.range = null;
        this.reverse = false;
        this.num = -1;
        this.from = reachableFrom;
        this.notFrom = unreachableFrom;
        this.format = String.join("%n",
                                  COMMIT_DELIMITER,
                                  GitCommitMetadata.FORMAT);

    }

    @Override
    public Iterator<Commit> iterator() {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "-c", "core.quotePath=false", "log", "--format=" + format,
                                         "--patch",
                                         "--find-renames=90%",
                                         "--find-copies=90%",
                                         "--find-copies-harder",
                                         "--topo-order",
                                         "--binary",
                                         "-c",
                                         "--combined-all-paths",
                                         "--raw",
                                         "--no-abbrev",
                                         "--unified=0",
                                         "--no-color"));
        if (reverse) {
            cmd.add("--reverse");
        }
        if (num > 0) {
            cmd.add("-n");
            cmd.add(Integer.toString(num));
        }
        if (range != null) {
            cmd.add(range);
        } else {
            cmd.addAll(from.stream().map(Hash::hex).toList());
            if (!notFrom.isEmpty()) {
                cmd.add("--not");
                cmd.addAll(notFrom.stream().map(Hash::hex).toList());
            }
        }
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.environment().putAll(GitRepository.currentEnv);
        var command = pb.command();
        try {
            var p = pb.start();
            processes.add(p);
            commands.add(command);
            var reader = new UnixStreamReader(p.getInputStream());

            return new GitCommitIterator(reader, COMMIT_DELIMITER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (!closed) {
                closed = true;
            } else {
                return;
            }
        }

        Exception exception = null;

        for (var i = 0; i < processes.size(); i++) {
            var p = processes.get(i);
            var command = commands.get(i);
            try {
                close(p, command);
            } catch (IOException | RuntimeException e) {
                if (exception == null) {
                    exception = e;
                } else {
                    exception.addSuppressed(e);
                }
            }
        }

        if (exception != null) {
            if (exception instanceof IOException) {
                throw (IOException) exception;
            } else {
                throw (RuntimeException) exception;
            }
        }
    }

    private void close(Process p, List<String> command) throws IOException {
        log.finer("Waiting for the process to terminate: pid=" + p.pid()
                + ", command=" + Arrays.toString(command.toArray()));
        try {
            var exited = p.waitFor(30L, TimeUnit.SECONDS);
            if (!exited) {
                throw new IOException("'" + String.join(" ", command) + "' timed out, pid=" + p.pid());
            }
            log.finer("Terminated: pid=" + p.pid());
            var exitCode = p.exitValue();
            if (exitCode != 0) {
                var stderr = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                var message = stderr.lines().collect(Collectors.joining("\n"));
                log.finer("stderr for pid=" + p.pid() + ": " + message);
                if (exitCode == 128) {
                    if (message.equals("fatal: bad default revision 'HEAD'")) {
                        // this is an empty repository, this is not an error case
                    } else {
                        throw new IOException("'" + String.join(" ", command) + "' exited with code: " + exitCode);
                    }
                }
            }
        } catch (InterruptedException e) {
            throw new IOException("'" + String.join(" ", command) + "' was interrupted", e);
        } finally {
            if (p.isAlive()) {
                log.finer("Destroying the process pid=" + p.pid());
                p.destroy();
            }
        }
    }
}
