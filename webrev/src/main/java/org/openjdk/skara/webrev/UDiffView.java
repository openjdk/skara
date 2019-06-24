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

import java.io.*;
import java.nio.file.*;
import java.util.*;

class UDiffView implements View {
    private Path out;
    private Path file;
    private TextualPatch patch;
    private Navigation nav;
    private final List<String> sourceContent;
    private final List<String> destContent;

    private static final int NUM_CONTEXT_LINES = 5;

    public UDiffView(Path out, Path file, TextualPatch patch, Navigation nav, List<String> sourceContent, List<String> destContent) {
        this.out = out;
        this.file = file;
        this.patch = patch;
        this.nav = nav;
        this.sourceContent = sourceContent;
        this.destContent = destContent;
    }

    private void writeContext(Writer w, List<Line> lines) throws IOException {
        for (var line : lines) {
            w.write("  ");
            w.write(HTML.escape(line.text()));
            w.write("\n");
        }
    }

    public void render(Writer w) throws IOException {
        var suffix = ".udiff.html";
        var udiffFile = out.resolve(file + suffix);
        Files.createDirectories(udiffFile.getParent());

        var map = new HashMap<String, String>();
        map.put("${TYPE}", "Udiff");
        map.put("${FILENAME}", file.toString());
        map.put("${CSS_URL}", Webrev.relativeToCSS(out, udiffFile));

        try (var fw = Files.newBufferedWriter(udiffFile)) {
            ViewUtils.DIFF_HEADER_TEMPLATE.render(fw, map);
            fw.write("\n");
            fw.write("<body>\n");
            ViewUtils.writeNavigation(out, fw, udiffFile, nav, suffix);
            ViewUtils.PRINT_FILE_TEMPLATE.render(fw, map);
            fw.write("\n");

            var coalescer = new HunkCoalescer(NUM_CONTEXT_LINES, sourceContent, destContent);
            for (var group : coalescer.coalesce(patch.hunks())) {
                fw.write("<hr />\n");
                fw.write("<pre>\n");

                fw.write("<span class=\"line-new-header\">@@ -");
                fw.write(HTML.escape(group.header().source().toString()));
                fw.write(" +");
                fw.write(HTML.escape(group.header().target().toString()));
                fw.write(" @@</span>\n");

                writeContext(fw, group.contextBefore().sourceLines());

                for (var hunk : group.hunks()) {
                    var numRemovedLines = hunk.removed().size();
                    var numAddedLines = hunk.added().size();

                    for (var i = 0; i < numRemovedLines; i++) {
                        if (i < numAddedLines) {
                            fw.write("<span class=\"udiff-line-modified-removed\">- ");
                        } else {
                            fw.write("<span class=\"udiff-line-removed\">- ");
                        }
                        fw.write(HTML.escape(hunk.removed().get(i).text()));
                        fw.write("</span>\n");
                    }
                    for (var i = 0; i < numAddedLines; i++) {
                        if (i < numRemovedLines) {
                            fw.write("<span class=\"udiff-line-modified-added\">+ ");
                        } else {
                            fw.write("<span class=\"udiff-line-added\">+ ");
                        }
                        fw.write(HTML.escape(hunk.added().get(i).text()));
                        fw.write("</span>\n");
                    }

                    writeContext(fw, hunk.contextAfter().destinationLines());
                }

                fw.write("</pre>\n");
            }

            ViewUtils.writeNavigation(out, fw, udiffFile, nav, suffix);
            ViewUtils.DIFF_FOOTER_TEMPLATE.render(fw, map);
        }

        w.write("<a href=\"");
        w.write(Webrev.relativeToIndex(out, udiffFile));
        w.write("\">Udiffs</a>\n");
    }
}

