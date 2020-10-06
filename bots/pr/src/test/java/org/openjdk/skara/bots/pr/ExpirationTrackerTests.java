/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class ExpirationTrackerTests {
    @Test
    void valid() {
        var text = "This is a text. " + ExpirationTracker.expiresAfterMarker(Duration.ofHours(10)) + ". Indeed.";
        assertFalse(ExpirationTracker.hasExpired(text));
    }

    @Test
    void expired() throws InterruptedException {
        var text = "This is a text. " + ExpirationTracker.expiresAfterMarker(Duration.ofMillis(1)) + ". Indeed.";
        Thread.sleep(10);
        assertTrue(ExpirationTracker.hasExpired(text));
    }

    @Test
    void multipleValid() {
        var text = "This is a text. " + ExpirationTracker.expiresAfterMarker(Duration.ofHours(10)) + ". Indeed." +
                "\n" + ExpirationTracker.expiresAfterMarker(Duration.ofDays(2));
        assertFalse(ExpirationTracker.hasExpired(text));
    }

    @Test
    void mixed() throws InterruptedException {
        var text = ExpirationTracker.expiresAfterMarker(Duration.ofDays(3)) + "\n" +
                "This is a text. " + ExpirationTracker.expiresAfterMarker(Duration.ofMillis(1)) + ". Indeed." +
                "\n" + ExpirationTracker.expiresAfterMarker(Duration.ofDays(2));
        Thread.sleep(10);
        assertTrue(ExpirationTracker.hasExpired(text));
    }
}
