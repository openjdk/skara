/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jbs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BuildCompareTests {
    @Test
    void simple() {
        assertTrue(BuildCompare.shouldReplace("main", "team"));
        assertFalse(BuildCompare.shouldReplace("team", "main"));

        assertTrue(BuildCompare.shouldReplace("b03", "team"));
        assertTrue(BuildCompare.shouldReplace("b03", "main"));
        assertTrue(BuildCompare.shouldReplace("b03", "b04"));

        assertFalse(BuildCompare.shouldReplace("team", "b03"));
        assertFalse(BuildCompare.shouldReplace("main", "b03"));
        assertFalse(BuildCompare.shouldReplace("b04", "b03"));

        assertTrue(BuildCompare.shouldReplace("team", null));
        assertTrue(BuildCompare.shouldReplace("main", null));
        assertTrue(BuildCompare.shouldReplace("b05", null));

        assertFalse(BuildCompare.shouldReplace("team", "team"));
        assertFalse(BuildCompare.shouldReplace("main", "main"));
        assertFalse(BuildCompare.shouldReplace("b12", "b12"));
    }
}
