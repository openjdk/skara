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
package org.openjdk.skara.vcs.hg;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.util.concurrent.TimeUnit;
import java.io.*;
import java.util.*;
import java.nio.file.Path;
import java.util.logging.Logger;

class HgCommits implements Commits, AutoCloseable {
    private final List<Process> processes = new ArrayList<Process>();
    private final List<List<String>> commands = new ArrayList<List<String>>();;
    private final Path dir;
    private final String range;
    private final String ext;
    private final boolean reverse;
    private final int num;
    private final Logger log = Logger.getLogger("org.openjdk.skara.vcs.hg");

    private boolean closed = false;

    public HgCommits(Path dir, String range, Path ext, boolean reverse, int num) throws IOException {
        this.dir = dir;
        this.range = range;
        this.ext = ext.toAbsolutePath().toString();
        this.reverse = reverse;
        this.num = num;
    }

    @Override
    public Iterator<Commit> iterator() {
        var command = new ArrayList<>(List.of("hg", "--config", "extensions.log-git=" + ext, "log-git"));
        if (reverse) {
            command.add("--reverse");
        }
        if (num > 0) {
            command.add("--limit");
            command.add(Integer.toString(num));
        }
        if (range != null) {
            command.add(range);
        }

        var pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("HGRCPATH", "");
        pb.environment().put("HGPLAIN", "");

        try {
            log.fine("Executing " + String.join(" ", command));
            var p = pb.start();
            processes.add(p);
            commands.add(command);

            var reader = new UnixStreamReader(p.getInputStream());
            return new HgCommitIterator(reader);
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
                if (exitCode != 0) {
                    throw new IOException("'" + String.join(" ", command) + "' exited with code: " + exitCode);
                }
            } catch (InterruptedException e) {
                throw new IOException("'" + String.join(" ", command) + "' was interrupted", e);
            }
        }
    }
}
