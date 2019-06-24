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

class SDiffView implements View {
    private final Path out;
    private final Path file;
    private final TextualPatch patch;
    private final Navigation nav;
    private final List<String> sourceContent;
    private final List<String> destContent;
    private final int maxLineNum;
    private final static int NUM_CONTEXT_LINES = 20;

    public SDiffView(Path out, Path file, TextualPatch patch, Navigation nav, List<String> sourceContent, List<String> destContent) {
        this.out = out;
        this.file = file;
        this.patch = patch;
        this.nav = nav;
        this.sourceContent = sourceContent;
        this.destContent = destContent;
        this.maxLineNum = Math.max(sourceContent.size(), destContent.size());
    }

    private void writeLine(Writer w, Line line) throws IOException {
        ViewUtils.writeWithLineNumber(w, line.text(), line.number(), maxLineNum);
    }

    private void writeContext(Writer w, Line line) throws IOException {
        writeLine(w, line);
        w.write("\n");
    }

    private void writeLine(Writer w, Line line, String kind) throws IOException {
        w.write("<span class=\"line-");
        w.write(kind);
        w.write("\">");
        writeLine(w, line);
        w.write("</span>\n");
    }

    public void render(Writer w) throws IOException {
        var suffix = ".sdiff.html";
        var sdiffFile = out.resolve(file + suffix);
        Files.createDirectories(sdiffFile.getParent());

        var map = new HashMap<String, String>();
        map.put("${TYPE}", "Sdiff");
        map.put("${FILENAME}", file.toString());
        map.put("${CSS_URL}", Webrev.relativeToCSS(out, sdiffFile));

        try (var fw = Files.newBufferedWriter(sdiffFile)) {
            ViewUtils.DIFF_HEADER_TEMPLATE.render(fw, map);
            fw.write("\n");
            fw.write("<body>\n");
            ViewUtils.writeNavigation(out, fw, sdiffFile, nav, suffix);
            ViewUtils.PRINT_FILE_TEMPLATE.render(fw, map);
            fw.write("\n");

            var coalescer = new HunkCoalescer(NUM_CONTEXT_LINES, sourceContent, destContent);
            var groups = coalescer.coalesce(patch.hunks());

            fw.write("<table>\n");
            fw.write("<tr valign=\"top\">\n");

            fw.write("<td>\n");
            for (var group : groups) {
                fw.write("<hr />\n");
                fw.write("<pre>\n");

                for (var line : group.contextBefore().sourceLines()) {
                    writeContext(fw, line);
                }

                for (var hunk : group.hunks()) {
                    var removed = hunk.removed();
                    var numRemoved = removed.size();
                    var numAdded = hunk.added().size();
                    var numModified = Math.min(numAdded, numRemoved);

                    for (var i = 0; i < numModified; i++) {
                        writeLine(fw, removed.get(i), "modified");
                    }

                    if (numRemoved > numModified) {
                        for (var i = numModified; i < numRemoved; i++) {
                            writeLine(fw, removed.get(i), "removed");
                        }
                    } else {
                        for (var i = numModified; i < numAdded; i++) {
                            fw.write("\n");
                        }
                    }

                    for (var line : hunk.contextAfter().sourceLines()) {
                        writeContext(fw, line);
                    }
                }
                fw.write("</pre>\n");
            }
            fw.write("</td>\n");

            fw.write("<td>\n");
            for (var group : groups) {
                fw.write("<hr />\n");
                fw.write("<pre>\n");

                for (var line : group.contextBefore().destinationLines()) {
                    writeContext(fw, line);
                }

                for (var hunk : group.hunks()) {
                    var added = hunk.added();
                    var numAdded = added.size();
                    var numRemoved = hunk.removed().size();
                    var numModified = Math.min(numAdded, numRemoved);

                    for (var i = 0; i < numModified; i++) {
                        writeLine(fw, added.get(i), "modified");
                    }

                    if (numAdded > numModified) {
                        for (var i = numModified; i < numAdded; i++) {
                            writeLine(fw, added.get(i), "added");
                        }
                    } else {
                        for (var i = numModified; i < numRemoved; i++) {
                            fw.write("\n");
                        }
                    }

                    for (var line : hunk.contextAfter().destinationLines()) {
                        writeContext(fw, line);
                    }
                }
                fw.write("</pre>\n");
            }
            fw.write("</td>\n");

            fw.write("</tr>\n");
            fw.write("</table>\n");

            ViewUtils.writeNavigation(out, fw, sdiffFile, nav, suffix);
            ViewUtils.DIFF_FOOTER_TEMPLATE.render(fw, map);
        }

        w.write("<a href=\"");
        w.write(Webrev.relativeToIndex(out, sdiffFile));
        w.write("\">Sdiffs</a>\n");
    }
}
