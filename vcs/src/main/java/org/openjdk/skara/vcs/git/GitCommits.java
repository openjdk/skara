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
package org.openjdk.skara.vcs.git;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.io.*;
import java.util.*;
import java.nio.file.Path;

class GitCommits implements Commits, AutoCloseable {
    private static final String COMMIT_DELIMITER = "#@!_-=&";

    private final Path dir;
    private final String range;
    private final boolean reverse;
    private final int num;
    private final String format;

    private final List<Process> processes = new ArrayList<Process>();
    private final List<List<String>> commands = new ArrayList<List<String>>();
    private boolean closed = false;

    public GitCommits(Path dir, String range, boolean reverse, int num) throws IOException {
        this.dir = dir;
        this.range = range;
        this.reverse = reverse;
        this.num = num;
        this.format = String.join("%n",
                                  COMMIT_DELIMITER,
                                  GitCommitMetadata.FORMAT);

    }

    @Override
    public Iterator<Commit> iterator() {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "log", "--format=" + format,
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
        cmd.add(range);
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
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

        for (var i = 0; i < processes.size(); i++) {
            var p = processes.get(i);
            var command = commands.get(i);
            try {
                var exited = p.waitFor(30L, TimeUnit.SECONDS);
                if (!exited) {
                    throw new IOException("'" + String.join(" ", command) + "' timed out");
                }
                var exitCode = p.exitValue();
                if (exitCode == 128) {
                    var stderr = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                    var message = stderr.readLine();
                    if (message.equals("fatal: bad default revision 'HEAD'")) {
                        // this is an empty repository, this is not an error case
                    } else {
                        throw new IOException("'" + String.join(" ", command) + "' exited with code: " + exitCode);
                    }
                }
            } catch (InterruptedException e) {
                throw new IOException("'" + String.join(" ", command) + "' was interrupted", e);
            }
        }
    }
}
