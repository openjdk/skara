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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitVersion {

    private static final Pattern versionPattern = Pattern.compile(
            "git version (?<versionString>.*(?<major>\\d)\\.(?<minor>\\d{2})\\.(?<security>\\d).*)");
    private static final GitVersion UNKNOWN = new GitVersion("UNKNOWN", -1, -1, -1);

    private final String versionString;
    private final int major;
    private final int minor;
    private final int security;

    private GitVersion(String versionString, int major, int minor, int security) {
        this.versionString = versionString;
        this.major = major;
        this.minor = minor;
        this.security = security;
    }

    public static GitVersion parse(String version) {
        var matcher = versionPattern.matcher(version);
        if (!matcher.find()) {
            return UNKNOWN;
        }

        return new GitVersion(
            matcher.group("versionString"),
            Integer.parseInt(matcher.group("major")),
            Integer.parseInt(matcher.group("minor")),
            Integer.parseInt(matcher.group("security"))
        );
    }

    public static GitVersion get() throws IOException {
        var p = new ProcessBuilder().command("git", "--version").start();
        try {
            var code = p.waitFor();
            if (code != 0) throw new IOException("git --version exited with code: " + code);
            try (var lines = new BufferedReader(new InputStreamReader(p.getInputStream())).lines()) {
                var linesList = lines.collect(Collectors.toList());
                for (var line : linesList) {
                    var version = parse(line);
                    if (version != UNKNOWN) {
                        return version;
                    }
                }
            }
            return UNKNOWN;
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public boolean isKnownSupported() {
        if (major < 2) {
            return false;
        }

        switch (minor) {
//            case 17:
//            case 19:
//                return security >= 4;
//
//            case 18:
//            case 20:
            case 22: // we require 2.22 since we use --combined-all-paths option of git log
            case 25:
                return security >= 3;

//            case 21:
            case 23:
            case 24:
                return security >= 2;

            default: {
                if (minor >= 26) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int security() {
        return security;
    }

    @Override
    public String toString() {
        return versionString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GitVersion that = (GitVersion) o;
        return Objects.equals(versionString, that.versionString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionString);
    }
}
