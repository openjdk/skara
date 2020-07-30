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
package org.openjdk.skara.vcs.git;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class GitVersionTest {

    static Stream<Arguments> supportedVersions() {
        return Stream.of(
            arguments("git version 2.22.3", 2, 22, 3),
            arguments("git version 2.23.2", 2, 23, 2),
            arguments("git version 2.24.2", 2, 24, 2),
            arguments("git version 2.25.3", 2, 25, 3),
            arguments("git version 2.26.1", 2, 26, 1),

            arguments("git version 2.27.0.windows.1", 2, 27, 0)
        );
    }

    @ParameterizedTest
    @MethodSource("supportedVersions")
    void testSupportedVersions(String versionsString, int major, int minor, int security) {
        GitVersion version = GitVersion.parse(versionsString);

        assertEquals(version.major(), major);
        assertEquals(version.minor(), minor);
        assertEquals(version.security(), security);

        assertFalse(version.isUnknown());
        assertTrue(version.isKnownSupported());
    }

    static Stream<Arguments> unsupportedVersions() {
        return Stream.of(
            arguments("git version 2.17.4", 2, 17, 4),
            arguments("git version 2.18.3", 2, 18, 3),
            arguments("git version 2.19.4", 2, 19, 4),
            arguments("git version 2.20.3", 2, 20, 3),
            arguments("git version 2.21.2", 2, 21, 2),
            arguments("git version 2.21.1 (Apple Git-122.3) ", 2, 21, 1) // doesn't contain security fix
        );
    }

    @ParameterizedTest
    @MethodSource("unsupportedVersions")
    void testUnsupportedVersions(String versionsString, int major, int minor, int security) {
        GitVersion version = GitVersion.parse(versionsString);

        assertEquals(version.major(), major);
        assertEquals(version.minor(), minor);
        assertEquals(version.security(), security);

        assertFalse(version.isUnknown());
        assertFalse(version.isKnownSupported());
    }

    static Stream<Arguments> unknownVersions() {
        return Stream.of(
            arguments("asdfxzxcv")
        );
    }

    @ParameterizedTest
    @MethodSource("unknownVersions")
    void testUnsupportedVersions(String versionsString) {
        GitVersion version = GitVersion.parse(versionsString);

        assertTrue(version.isUnknown());
        assertFalse(version.isKnownSupported());
    }

}
