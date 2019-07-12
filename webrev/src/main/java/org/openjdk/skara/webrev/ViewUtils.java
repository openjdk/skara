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

class ViewUtils {
    static final Template DIFF_HEADER_TEMPLATE = new Template(new String[]{
        "<!DOCTYPE html>",
        "<html>",
        "  <head>",
        "    <meta charset=\"utf-8\" />",
        "    <title>${TYPE} ${FILENAME}</title>",
        "    <link rel=\"stylesheet\" href=\"${CSS_URL}\" />",
        "  </head>",
    });

    static final Template DIFF_FOOTER_TEMPLATE = new Template(new String[]{
        "  </body>",
        "</html>"
    });

    static final Template PRINT_FILE_TEMPLATE = new Template(new String[]{
        "    <h2>${FILENAME}</h2>",
        "     <a class=\"print\" href=\"javascript:print()\">Print this page</a>",
    });

    public static void writeNavigation(Path out, Writer w, Path current, Navigation nav, String suffix) throws IOException {
        w.write("<center>");
        if (nav.previous() != null) {
            w.write("<a href=\"");
            w.write(Webrev.relativeTo(current, out.resolve(nav.previous())) + suffix);
            w.write("\" target=\"_top\">");
            w.write(HTML.escape("< prev"));
            w.write("</a>");
        } else {
            w.write(HTML.escape("< prev"));
        }

        w.write(" ");
        w.write("<a href=\"");
        w.write(Webrev.relativeTo(current, out.resolve("index.html")));
        w.write("\" target=\"_top\">index</a>");
        w.write(" ");

        if (nav.next() != null) {
            w.write("<a href=\"");
            w.write(Webrev.relativeTo(current, out.resolve(nav.next())) + suffix);
            w.write("\" target=\"_top\">");
            w.write(HTML.escape("next >"));
            w.write("</a>");
        } else {
            w.write(HTML.escape("next >"));
        }

        w.write("</center>");
    }

    public static int numChars(int n) {
        if (n < 0) {
            throw new RuntimeException("Negative number: " + n);
        }

        if (n < 10)       return 1;
        if (n < 100)      return 2;
        if (n < 1000)     return 3;
        if (n < 10000)    return 4;
        if (n < 100000)   return 5;
        if (n < 1000000)  return 6;
        if (n < 10000000) return 7;

        throw new RuntimeException("Too long number: " + n);
    }

    public static void writeWithLineNumber(Writer writer, String line, int lineNumber, int maxLineNumber) throws IOException {
        var numSpace = numChars(maxLineNumber) - numChars(lineNumber);
        for (var i = 0; i < numSpace; i++) {
            writer.write(" ");
        }
        writer.write(String.valueOf(lineNumber));
        writer.write(" ");
        writer.write(HTML.escape(line));
    }

    public static String pathWithUnixSeps(Path p) {
        return p.toString().replace('\\', '/');
    }
}
