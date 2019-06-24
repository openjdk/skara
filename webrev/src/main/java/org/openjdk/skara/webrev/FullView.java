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

import java.io.*;
import java.nio.file.*;
import java.util.*;

class FullView implements View {
    private final Path out;
    private final Path path;
    private final List<String> content;
    private final String suffix;
    private final String title;

    public FullView(Path out, Path path, List<String> content, String suffix, String title) {
        this.out = out;
        this.path = path;
        this.content = content;
        this.suffix = suffix;
        this.title = title;
    }

    public void render(Writer w) throws IOException {
        var file = out.resolve(path.toString() + suffix);
        Files.createDirectories(file.getParent());

        var map = new HashMap<String, String>();
        map.put("${TYPE}", title);
        map.put("${FILENAME}", path.toString());
        map.put("${CSS_URL}", Webrev.relativeToCSS(out, file));

        try (var fw = Files.newBufferedWriter(file)) {
            ViewUtils.DIFF_HEADER_TEMPLATE.render(fw, map);
            fw.write("\n");
            fw.write("  <body>\n");
            fw.write("    <pre>\n");

            var maxLineNumber = content.size();
            for (var i = 0; i < content.size(); i++) {
                ViewUtils.writeWithLineNumber(fw, content.get(i), i + 1, maxLineNumber);
                fw.write("\n");
            }

            fw.write("    </pre>\n");
            ViewUtils.DIFF_FOOTER_TEMPLATE.render(fw, map);
        }

        w.write("<a href=\"");
        w.write(Webrev.relativeToIndex(out, file));
        w.write("\">");
        w.write(title);
        w.write("</a>\n");
    }
}

class OldView extends FullView {
    public OldView(Path out, Path path, List<String> content) {
        super(out, path, content, "-.html", "Old");
    }
}

class NewView extends FullView {
    public NewView(Path out, Path path, List<String> content) {
        super(out, path, content, ".html", "New");
    }
}

