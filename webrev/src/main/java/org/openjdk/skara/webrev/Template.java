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
import java.util.Map;

class Template {
    private final String[] template;

    public Template(String[] template) {
        this.template = template;
    }

    public void render(Writer w, Map<String, String> map) throws IOException {
        for (var i = 0; i < template.length; i++) {
            var s = template[i];
            for (var key : map.keySet()) {
                if (key.endsWith("_URL}")) {
                    s = s.replace(key, map.get(key));
                } else {
                    s = s.replace(key, HTML.escape(map.get(key)));
                }
            }
            w.write(s);

            if (i != template.length - 1) {
                w.write("\n");
            }
        }
    }
}
