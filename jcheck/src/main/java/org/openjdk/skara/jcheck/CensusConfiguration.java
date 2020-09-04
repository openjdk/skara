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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.ini.Section;
import java.net.URI;

public class CensusConfiguration {
    private static final CensusConfiguration DEFAULT =
        new CensusConfiguration(0, "localhost", URI.create("https://openjdk.java.net/census.xml"));

    private final int version;
    private final String domain;
    private final URI url;

    CensusConfiguration(int version, String domain, URI url) {
        this.version = version;
        this.domain = domain;
        this.url = url;
    }

    public int version() {
        return version;
    }

    public String domain() {
        return domain;
    }

    public URI url() {
        return url;
    }

    static String name() {
        return "census";
    }

    static CensusConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var version = s.get("version", DEFAULT.version());
        var domain = s.get("domain", DEFAULT.domain());
        var url = s.get("url", DEFAULT.url().toString());
        return new CensusConfiguration(version, domain, URI.create(url));
    }
}
