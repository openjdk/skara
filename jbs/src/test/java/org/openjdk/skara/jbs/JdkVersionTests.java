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

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JdkVersionTests {
    private JdkVersion from(String raw) {
        return JdkVersion.parse(raw).orElseThrow();
    }

    @Test
    void jep223() {
        assertEquals(List.of("8"), from("8").components());
        assertEquals(List.of("9", "0", "4"), from("9.0.4").components());
        assertEquals(List.of("10", "0", "2"), from("10.0.2").components());
        assertEquals(List.of("11"), from("11").components());
        assertEquals(List.of("11", "0", "3"), from("11.0.3").components());
        assertEquals(List.of("12", "0", "2"), from("12.0.2").components());
    }

    @Test
    void jep322() {
        assertEquals(List.of("11", "0", "2", "0", "1"), from("11.0.2.0.1-oracle").components());
        assertEquals("oracle", from("11.0.2.0.1-oracle").opt().orElseThrow());
        assertEquals(List.of("11", "0", "3"), from("11.0.3-oracle").components());
        assertEquals("oracle", from("11.0.3-oracle").opt().orElseThrow());
        var fooVersion = from("11.0.12-foo-bar");
        assertEquals(List.of("11", "0", "12"), fooVersion.components());
        assertEquals("foo-bar", fooVersion.opt().orElseThrow());
    }

    @Test
    void legacy() {
        assertEquals(List.of("5.0", "45"), from("5.0u45").components());
        assertEquals(List.of("6", "201"), from("6u201").components());
        assertEquals(List.of("7", "40"), from("7u40").components());
        assertEquals(List.of("8", "211"), from("8u211").components());
        assertEquals(List.of("emb-8", "171"), from("emb-8u171").components());
        assertEquals(List.of("hs22", "4"), from("hs22.4").components());
        assertEquals(List.of("hs23"), from("hs23").components());
        assertEquals(List.of("openjdk7"), from("openjdk7").components());
        assertEquals(List.of("openjdk8"), from("openjdk8").components());
        assertEquals(List.of("openjdk8", "211"), from("openjdk8u211").components());
        assertEquals(List.of("shenandoah8", "211"), from("shenandoah8u211").components());
        assertEquals(List.of("foobar8", "211"), from("foobar8u211").components());
    }

    @Test
    void openjfx11() {
        assertEquals(List.of("openjfx11", "0", "12"), from("openjfx11.0.12").components());
        assertEquals(List.of("openjfx17", "3", "4", "5", "6"), from("openjfx17.3.4.5.6").components());
    }


    @Test
    void futureUpdates() {
        assertEquals(List.of("16u"), from("16u").components());
        var jdk16uCpu = from("16u-cpu");
        assertEquals(List.of("16u"), jdk16uCpu.components());
        assertEquals("cpu", jdk16uCpu.opt().orElseThrow());
        assertEquals(List.of("openjdk7u"), from("openjdk7u").components());
        var jfx20uCpu = from("jfx20u-cpu");
        assertEquals(List.of("jfx20u"), jfx20uCpu.components());
        assertEquals("cpu", jfx20uCpu.opt().orElseThrow());
    }

    @Test
    void jdkCpu() {
        var jdkCpu = from("jdk-cpu");
        assertEquals(List.of("jdk"), jdkCpu.components());
        assertEquals("cpu", jdkCpu.opt().orElseThrow());
    }

    @Test
    void jfxCpu() {
        var jfxCpu = from("jfx-cpu");
        assertEquals(List.of("jfx"), jfxCpu.components());
        assertEquals("cpu", jfxCpu.opt().orElseThrow());
    }

    @Test
    void order() {
        assertEquals(0, from("5.0u45").compareTo(from("5.0u45")));
        assertEquals(0, from("11.0.3").compareTo(from("11.0.3")));
        assertEquals(0, from("11.0.2.0.1-oracle").compareTo(from("11.0.2.0.1-oracle")));

        assertEquals(1, from("6u201").compareTo(from("5.0u45")));
        assertEquals(-1, from("5.0u45").compareTo(from("6u201")));

        assertEquals(-1, from("11.0.2.0.1").compareTo(from("11.0.2.0.1-oracle")));
        assertEquals(1, from("11.0.2.0.1-oracle").compareTo(from("11.0.2.0.1")));

        assertEquals(-1, from("9.0.4").compareTo(from("10.0.2")));
        assertEquals(-1, from("11").compareTo(from("11.0.3")));
        assertEquals(-1, from("emb-8u171").compareTo(from("emb-8u175")));
        assertEquals(-1, from("emb-8u71").compareTo(from("emb-8u170")));
        assertEquals(-1, from("openjdk7").compareTo(from("openjdk7u42")));
        assertEquals(-1, from("hs22.4").compareTo(from("hs23")));
        assertEquals(-1, from("openjfx11.0.12").compareTo(from("openjfx17.3.4.5.6")));
    }

    @Test
    void cpuOrder() {
        assertEquals(-1, from("16").compareTo(from("16u-cpu")));
        assertEquals(-1, from("16.0.2").compareTo(from("16u-cpu")));
        assertEquals(1, from("17").compareTo(from("16u-cpu")));
    }

    @Test
    void jdkCpuOrder() {
        assertTrue(from("16").compareTo(from("jdk-cpu")) < 0);
        assertTrue(from("16.0.2").compareTo(from("jdk-cpu")) < 0);
        assertTrue(from("17").compareTo(from("jdk-cpu")) < 0);
    }

    @Test
    void nonConforming() {
        assertEquals(Optional.empty(), JdkVersion.parse("bla"));
        assertEquals(Optional.empty(), JdkVersion.parse(""));
    }

    @Test
    void legacyOpt() {
        assertEquals(List.of("8", "333"), from("8u333-foo").components());
        assertEquals("foo", from("8u333-foo").opt().orElseThrow());
    }

    @Test
    void teamRepo() {
        assertEquals(List.of("repo", "foo"), from("repo-foo").components());
        assertTrue(from("20").compareTo(from("repo-foo")) < 0);
    }

    @Test
    void teamBranch() {
        assertEquals(List.of("branch", "foo"), from("branch-foo").components());
        assertTrue(from("20").compareTo(from("branch-foo")) < 0);
    }
}
