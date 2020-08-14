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
package org.openjdk.skara.vcs;

import java.io.IOException;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class Diff {
    private final Hash from;
    private final Hash to;
    private final List<Patch> patches;

    public Diff(Hash from, Hash to, List<Patch> patches) {
        this.from = from;
        this.to = to;
        this.patches = patches;
    }

    public Hash from() {
        return from;
    }

    public Hash to() {
        return to;
    }

    public List<Patch> patches() {
        return patches;
    }

    public List<WebrevStats> stats() {
        return patches().stream()
                        .filter(Patch::isTextual)
                        .map(Patch::asTextualPatch)
                        .map(TextualPatch::stats)
                        .collect(Collectors.toList());
    }

    public WebrevStats totalStats() {
        var added = stats().stream().mapToInt(WebrevStats::added).sum();
        var removed = stats().stream().mapToInt(WebrevStats::removed).sum();
        var modified = stats().stream().mapToInt(WebrevStats::modified).sum();
        return new WebrevStats(added, removed, modified);
    }

    public void write(BufferedWriter w) throws IOException {
        for (var patch : patches()) {
            patch.write(w);
        }
    }

    public void toFile(Path p) throws IOException {
        try (var w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            write(w);
        }
    }
}
