/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.args;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InputTests {
    @Test
    void trailingToString() {
        var i = Input.position(0)
                     .describe("ARG")
                     .trailing()
                     .required();
        assertEquals("<ARG>...", i.toString());
    }

    @Test
    void singleToString() {
        var i = Input.position(0)
                     .describe("ARG")
                     .singular()
                     .required();
        assertEquals("<ARG>", i.toString());
    }

    @Test
    void multipleToString() {
        var i = Input.position(0)
                     .describe("ARG")
                     .multiple(2)
                     .required();
        assertEquals("<ARG> <ARG>", i.toString());
    }

    @Test
    void optionalToString() {
        var i = Input.position(0)
                     .describe("ARG")
                     .singular()
                     .optional();
        assertEquals("[<ARG>]", i.toString());
    }
}
