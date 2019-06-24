/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class BinaryHunk {
    private boolean isLiteral;
    private final int inflatedSize;
    private final List<String> data; // base85 encoded with leading size character

    private BinaryHunk(boolean isLiteral, int inflatedSize, List<String> data) {
        this.isLiteral = isLiteral;
        this.inflatedSize = inflatedSize;
        this.data = data;
    }

    public static BinaryHunk ofLiteral(int inflatedSize, List<String> data) {
        return new BinaryHunk(true, inflatedSize, data);
    }

    public static BinaryHunk ofDelta(int inflatedSize, List<String> data) {
        return new BinaryHunk(false, inflatedSize, data);
    }

    public int inflatedSize() {
        return inflatedSize;
    }

    public List<String> data() {
        return data;
    }

    public boolean isLiteral() {
        return isLiteral;
    }

    public boolean isDelta() {
        return !isLiteral;
    }

    public void write(BufferedWriter w) throws IOException {
        if (isLiteral()) {
            w.append("literal ");
        } else {
            w.append("delta ");
        }
        w.append(Integer.toString(inflatedSize));
        w.newLine();

        for (var line : data) {
            w.append(line);
            w.newLine();
        }

        w.newLine();
    }
}
