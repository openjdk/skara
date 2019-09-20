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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.TextualPatch;
import org.openjdk.skara.vcs.BinaryPatch;

import java.io.*;
import java.nio.file.*;
import java.util.List;

class PatchView implements View {
    private final Path out;
    private final Path file;
    private final TextualPatch textualPatch;
    private final BinaryPatch binaryPatch;
    private final List<String> sourceContent;
    private final List<String> destContent;
    private static final int NUM_CONTEXT_LINES = 5;

    public PatchView(Path out, Path file, TextualPatch patch, List<String> sourceContent, List<String> destContent) {
        this.out = out;
        this.file = file;
        this.textualPatch = patch;
        this.binaryPatch = null;
        this.sourceContent = sourceContent;
        this.destContent = destContent;
    }

    public PatchView(Path out, Path file, BinaryPatch patch) {
        this.out = out;
        this.file = file;
        this.textualPatch = null;
        this.binaryPatch = patch;
        this.sourceContent = null;
        this.destContent = null;
    }

    private void writeLine(Writer w, String prepend, Line line) throws IOException {
        w.write(prepend);
        w.write(line.text());
        w.write("\n");
    }

    @Override
    public void render(Writer w) throws IOException {
        var patchFile = out.resolve(file.toString() + ".patch");
        Files.createDirectories(patchFile.getParent());

        if (binaryPatch != null) {
            renderBinary(patchFile);
        } else {
            renderTextual(patchFile);
        }

        w.write("<a href=\"");
        w.write(Webrev.relativeToIndex(out, patchFile));
        w.write("\">Patch</a>\n");
    }

    private void renderBinary(Path patchFile) throws IOException {
        try (var fw = Files.newBufferedWriter(patchFile)) {
            var sourcePath = ViewUtils.pathWithUnixSeps(binaryPatch.source().path().get());
            var targetPath = ViewUtils.pathWithUnixSeps(binaryPatch.target().path().get());
            fw.write("diff a/");
            fw.write(sourcePath);
            fw.write(" b/");
            fw.write(targetPath);
            fw.write("\n");
            fw.write("Binary files ");
            fw.write(sourcePath);
            fw.write(" and ");
            fw.write(targetPath);
            fw.write(" differ\n");
        }

    }

    private void renderTextual(Path patchFile) throws IOException {
        try (var fw = Files.newBufferedWriter(patchFile)) {
            fw.write("diff a/");
            fw.write(ViewUtils.pathWithUnixSeps(textualPatch.source().path().get()));
            fw.write(" b/");
            fw.write(ViewUtils.pathWithUnixSeps(textualPatch.target().path().get()));
            fw.write("\n");
            fw.write("--- a/");
            fw.write(ViewUtils.pathWithUnixSeps(textualPatch.source().path().get()));
            fw.write("\n");
            fw.write("+++ b/");
            fw.write(ViewUtils.pathWithUnixSeps(textualPatch.target().path().get()));
            fw.write("\n");

            var coalescer = new HunkCoalescer(NUM_CONTEXT_LINES, sourceContent, destContent);
            for (var group : coalescer.coalesce(textualPatch.hunks())) {
                var sourceRange = group.header().source();
                var destRange = group.header().target();

                fw.write("@@ -");
                fw.write(String.valueOf(sourceRange.start()));
                fw.write(",");
                fw.write(String.valueOf(sourceRange.count()));
                fw.write(" +");
                fw.write(String.valueOf(destRange.start()));
                fw.write(",");
                fw.write(String.valueOf(destRange.count()));
                fw.write(" @@\n");

                for (var line : group.contextBefore().sourceLines()) {
                    writeLine(fw, " ", line);
                }

                for (var hunk : group.hunks()) {
                    for (var line : hunk.removed()) {
                        writeLine(fw, "-", line);
                    }
                    for (var line : hunk.added()) {
                        writeLine(fw, "+", line);
                    }
                    for (var line : hunk.contextAfter().sourceLines()) {
                        writeLine(fw, " ", line);
                    }
                }
            }
        }
    }
}
