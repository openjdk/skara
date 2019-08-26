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

class FramesView implements View {
    private final Path out;
    private final Path file;
    private final TextualPatch patch;
    private final Navigation nav;
    private final List<String> sourceContent;
    private final List<String> destContent;
    private final static int numContextLines = 20;

    public FramesView(Path out, Path file, TextualPatch patch, Navigation nav, List<String> sourceContent, List<String> destContent) {
        this.out = out;
        this.file = file;
        this.patch = patch;
        this.nav = nav;
        this.sourceContent = sourceContent;
        this.destContent = destContent;
    }

    public void render(Writer w) throws IOException {
        var suffix = ".frames.html";
        var framesFile = out.resolve(file + suffix);
        Files.createDirectories(framesFile.getParent());

        var header = new Template(new String[]{
            "<!DOCTYPE html>",
            "<html>",
            "  <head>",
            "    <meta charset=\"utf-8\" />",
            "    <title>${TYPE} ${FILENAME}</title>",
            "    <link rel=\"stylesheet\" href=\"${CSS_URL}\" />",
            "    <script type=\"text/javascript\" src=\"${JS_URL}\"></script>",
            "  </head>",
            "<body onkeypress=\"keypress(event);\">",
            "<a name=\"0\"></a>",
            "<hr />",
            "<pre>"
        });

        var footer = new Template(new String[]{
            "</pre>",
            "<input id=\"eof\" value=\"${EOF_VALUE}\" type=\"hidden\" />",
            "</body>",
            "</html>"
        });

        final var eofValue = patch.hunks().size() + 1;

        var map = new HashMap<String, String>();
        map.put("${TYPE}", "Frames");
        map.put("${FILENAME}", file.toString());
        map.put("${CSS_URL}", Webrev.relativeToCSS(out, framesFile));
        map.put("${JS_URL}", Webrev.relativeToAncnavJS(out, framesFile));
        map.put("${EOF_VALUE}", String.valueOf(eofValue));

        var oldFrame = out.resolve(file + ".lhs.html");
        var lastEnd = 0;
        var maxLineNum = sourceContent.size();
        try (var fw = Files.newBufferedWriter(oldFrame)) {
            header.render(fw, map);
            var hunks = patch.hunks();
            for (var hunkIndex = 0; hunkIndex < hunks.size(); hunkIndex++) {
                var hunk = hunks.get(hunkIndex);
                var numSourceLines = hunk.source().lines().size();
                var numDestLines = hunk.target().lines().size();
                var start = numSourceLines == 0 && hunk.source().range().start() == 0 ?
                    hunk.source().range().start() :
                    hunk.source().range().start() - 1;

                for (var i = lastEnd; i < start; i++) {
                    ViewUtils.writeWithLineNumber(fw, sourceContent.get(i), i + 1, maxLineNum);
                    fw.write("\n");
                }
                var anchorId = hunkIndex + 1;
                fw.write(String.format("<a name=\"%d\" id=\"anc%d\"></a>", anchorId, anchorId));
                for (var i = 0; i < numSourceLines; i++) {
                    if (i < numDestLines) {
                        fw.write("<span class=\"line-modified\">");
                    } else {
                        fw.write("<span class=\"line-removed\">");
                    }
                    ViewUtils.writeWithLineNumber(fw, sourceContent.get(start + i), start + i + 1, maxLineNum);
                    fw.write("</span>\n");
                }
                for (var i = numSourceLines; i < numDestLines; i++) {
                    fw.write("\n");
                }
                lastEnd = start + numSourceLines;
            }

            for (var i = lastEnd; i < maxLineNum; i++) {
                ViewUtils.writeWithLineNumber(fw, sourceContent.get(i), i + 1, maxLineNum);
                fw.write("\n");
            }

            fw.write(String.format("<a name=\"%d\" id=\"anc%d\"></a>", eofValue, eofValue));
            fw.write("<b style=\"font-size: large; color: red\">--- EOF ---</b>\n");
            for (var i = 0; i < 80; i++) {
                fw.write("\n");
            }
            footer.render(fw, map);
        }

        var newFrame = out.resolve(file + ".rhs.html");
        lastEnd = 0;
        maxLineNum = destContent.size();
        try (var fw = Files.newBufferedWriter(newFrame)) {
            header.render(fw, map);
            var hunks = patch.hunks();
            for (var hunkIndex = 0; hunkIndex < hunks.size(); hunkIndex++) {
                var hunk = hunks.get(hunkIndex);
                var numSourceLines = hunk.source().lines().size();
                var numDestLines = hunk.target().lines().size();
                var start = numDestLines == 0 && hunk.target().range().start() == 0 ?
                    hunk.target().range().start() :
                    hunk.target().range().start() - 1;

                for (var i = lastEnd; i < start; i++) {
                    ViewUtils.writeWithLineNumber(fw, destContent.get(i), i + 1, maxLineNum);
                    fw.write("\n");
                }
                var anchorId = hunkIndex + 1;
                fw.write(String.format("<a name=\"%d\" id=\"anc%d\"></a>", anchorId, anchorId));
                for (var i = 0; i < numDestLines; i++) {
                    if (i < numSourceLines) {
                        fw.write("<span class=\"line-modified\">");
                    } else {
                        fw.write("<span class=\"line-added\">");
                    }
                    ViewUtils.writeWithLineNumber(fw, destContent.get(start + i), start + i + 1, maxLineNum);
                    fw.write("</span>\n");
                }
                for (var i = numDestLines; i < numSourceLines; i++) {
                    fw.write("\n");
                }
                lastEnd = start + numDestLines;
            }

            for (var i = lastEnd; i < maxLineNum; i++) {
                ViewUtils.writeWithLineNumber(fw, destContent.get(i), i + 1, maxLineNum);
                fw.write("\n");
            }
            fw.write(String.format("<a name=\"%d\" id=\"anc%d\"></a>", eofValue, eofValue));
            fw.write("<b style=\"font-size: large; color: red\">--- EOF ---</b>\n");
            for (var i = 0; i < 80; i++) {
                fw.write("\n");
            }
            footer.render(fw, map);
        }

        var framesNavigation = out.resolve(file + ".frames.prev_next.html");
        try (var fw = Files.newBufferedWriter(framesNavigation)) {
            ViewUtils.DIFF_HEADER_TEMPLATE.render(fw, map);
            fw.write("<body>\n");
            ViewUtils.writeNavigation(out, fw, framesFile, nav, ".frames.html");
            ViewUtils.DIFF_FOOTER_TEMPLATE.render(fw, map);
        }

        try (var fw = Files.newBufferedWriter(framesFile)) {
            ViewUtils.DIFF_HEADER_TEMPLATE.render(fw, map);
            fw.write("\n");
            fw.write("<frameset rows=\"*,90\">\n");
            fw.write("  <frameset cols=\"50%,50%\">\n");
            fw.write("      <frame src=\"");
            fw.write(oldFrame.getFileName().toString());
            fw.write("\" scrolling=\"auto\" name=\"oldFrame\" />\n");
            fw.write("      <frame src=\"");
            fw.write(newFrame.getFileName().toString());
            fw.write("\" scrolling=\"auto\" name=\"newFrame\" />\n");
            fw.write("  </frameset>\n");
            fw.write("  <frameset rows=\"60,30\">\n");
            fw.write("      <frame src=\"");
            fw.write(Webrev.relativeToAncnavHTML(out, framesFile));
            fw.write("\" scrolling=\"no\" marginwidth=\"0\" marginheight=\"0\" name=\"navigationFrame\" />\n");
            fw.write("      <frame src=\"");
            fw.write(framesNavigation.getFileName().toString());
            fw.write("\" scrolling=\"no\" marginwidth=\"0\" marginheight=\"0\" name=\"prev_next\" />\n");  
            fw.write("  </frameset>\n");
            fw.write("</html>\n");
        }

        w.write("<a href=\"");
        w.write(Webrev.relativeToIndex(out, framesFile));
        w.write("\">Frames</a>\n");
    }
}
