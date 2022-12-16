/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs.openjdk;

import org.openjdk.skara.vcs.Tag;

import java.util.*;
import java.util.regex.Pattern;

public class OpenJDKTag {
    private final Tag tag;
    private final String prefix;
    private final String version;
    private final String buildPrefix;
    private final String buildNum;

    private OpenJDKTag(Tag tag, String prefix, String version, String buildPrefix, String buildNum) {
        this.tag = tag;
        this.prefix = prefix;
        this.version = version;
        this.buildPrefix = buildPrefix;
        this.buildNum = buildNum;
    }

    /**
     * The patterns have the following groups:
     *
     *                     prefix       version   buildPrefix  buildNum
     *                     -------      -------   -----------  ------
     * jdk-9.1+27       -> jdk-9.1      9.1       +            27
     * jdk8-b90         -> jdk8         8         -b           90
     * jdk7u40-b20      -> jdk7u40      7u40      -b           29
     * hs24-b30         -> hs24         24        -b           30
     * hs23.6-b19       -> hs23.6       23.6      -b           19
     * 11.1+22          -> 11.1         11.1      +            22
     * 8u321-b03        -> 8u321        8u321     -b           3
     * jdk8u341-foo-b17 -> jdk8u341-foo 8u341-foo -b           17
     * foo8u341-b17     -> foo8u341     foo8u341  -b           17
     */

    private final static String legacyOpenJDKVersionPattern = "(jdk([0-9]{1,2}(u[0-9]{1,3}(?:-[a-z0-9]+)?)?))";
    private final static String legacyHSVersionPattern = "((hs[0-9]{1,2}(\\.[0-9]{1,3})?))";
    private final static String legacyBuildPattern = "(-b)([0-9]{2,3})";
    // Version pattern matching project Verona (JEP 223) based versions
    private final static String veronaVersionPattern = "((?:jdk-){0,1}([1-9](?:(?:[0-9]*)(\\.(?:0|[1-9][0-9]*)){0,6})))(?:(\\+)([0-9]+)|(-ga))";
    private final static String legacyOpenJFXVersionPattern = "(([0-9](u[0-9]{1,3})?))";
    private final static String legacyOpenJDKProjectVersionPattern = "(([a-z]+[0-9]{1,2}(u[0-9]{1,3}(?:-[a-z0-9]+)?)?))";

    private final static List<Pattern> tagPatterns = List.of(Pattern.compile(legacyOpenJDKVersionPattern + legacyBuildPattern),
                                                             Pattern.compile(legacyHSVersionPattern + legacyBuildPattern),
                                                             Pattern.compile(veronaVersionPattern),
                                                             Pattern.compile(legacyOpenJFXVersionPattern + legacyBuildPattern),
                                                             Pattern.compile(legacyOpenJDKProjectVersionPattern + legacyBuildPattern));

    /**
     * Attempts to create an OpenJDKTag instance from a general Tag.
     *
     * This will succeed if the tag follows the OpenJDK tag formatting
     * conventions.
     * @param tag
     * @return
     */
    public static Optional<OpenJDKTag> create(Tag tag) {
        for (var pattern : tagPatterns) {
            var matcher = pattern.matcher(tag.name());
            if (matcher.matches()) {
                return Optional.of(new OpenJDKTag(tag, matcher.group(1), matcher.group(2), matcher.group(4), matcher.group(5)));
            }
        }

        return Optional.empty();
    }

    /**
     * The original Tag this OpenJDKTag was created from.
     *
     * @return
     */
    public Tag tag() {
        return tag;
    }

    /**
     * Version number, such as 11, 9.1, 8, 7u20.
     *
     * @return
     */
    public String version() {
        return version;
    }

    /**
     * The complete prefix, which is everything except the build number and any
     * delimiter before it (e.g. jdk8u20, shenandoah8u332, jdk8u333-foo)
     *
     * @return
     */
    public String prefix() {
        return prefix;
    }

    /**
     * Build number.
     *
     * @return
     */
    public Optional<Integer> buildNum() {
        if (buildNum == null) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(buildNum));
    }

    /**
     * Tag of the previous build (if any). Build 0 (and no build number at all) have no previous build.
     *
     * @return
     */
    public Optional<OpenJDKTag> previous() {
        if (buildNum().orElse(0) == 0) {
            return Optional.empty();
        }

        // Make sure build numbers < 10 for JDK 9 tags are not prefixed with '0'
        var previousBuildNum = buildNum().get() - 1;
        var formattedBuildNum = String.format(buildPrefix.equals("+") ? "%d" : "%02d", previousBuildNum);
        var tagName = prefix + buildPrefix + formattedBuildNum;
        var tag = new Tag(tagName);
        return create(tag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OpenJDKTag that = (OpenJDKTag) o;
        return tag.equals(that.tag) &&
                prefix.equals(that.prefix) &&
                version.equals(that.version) &&
                buildPrefix.equals(that.buildPrefix) &&
                buildNum.equals(that.buildNum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, prefix, version, buildPrefix, buildNum);
    }

    @Override
    public String toString() {
        return tag.toString();
    }
}
