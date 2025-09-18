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

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JdkVersion implements Comparable<JdkVersion> {
    private final String raw;
    private final List<String> components;
    private final String opt;
    private final String build;

    private final static Pattern jdkVersionPattern = Pattern.compile("(5\\.0|[1-9][0-9]?)(u([0-9]{1,3}))?(?:-(.*))?$");
    private final static Pattern hsxVersionPattern = Pattern.compile("(hs[1-9][0-9]{1,2})(\\.([0-9]{1,3}))?$");
    private final static Pattern embVersionPattern = Pattern.compile("(emb-[8-9])(u([0-9]{1,3}))?$");
    // Accept any lower case letter prefix, such as 'openjdk', 'openjfx' or 'shenandoah'.
    private final static Pattern prefixVersionPattern = Pattern.compile("([a-z]+[1-9][0-9]?)(u([0-9]{1,3}))?$");

    // Match a version string symbolizing some future, but yet undefined, update of a major version
    private final static Pattern futureUpdatePattern = Pattern.compile("(([a-z])*[1-9][0-9]*u)(-([a-z0-9]+))?$");

    private final static Pattern prefixPattern = Pattern.compile("([a-z]+)([0-9.]+)$");

    private final static Pattern legacyPrefixPattern = Pattern.compile("^([^\\d]*)\\d+$");

    private final static Pattern projectRepoPattern = Pattern.compile("(repo|branch)-([a-z0-9]*)");

    private static List<String> splitComponents(String raw) {
        var finalComponents = new ArrayList<String>();

        // First check for the legacy patterns
        for (var legacyPattern : List.of(jdkVersionPattern, hsxVersionPattern, embVersionPattern, prefixVersionPattern)) {
            var legacyMatcher = legacyPattern.matcher(raw);
            if (legacyMatcher.matches()) {
                finalComponents.add(legacyMatcher.group(1));
                if (legacyMatcher.group(3) != null) {
                    finalComponents.add(legacyMatcher.group(3));
                }
                if (legacyMatcher.groupCount() >= 4 && legacyMatcher.group(4) != null) {
                    finalComponents.add(null);
                    finalComponents.add(legacyMatcher.group(4));
                }
                break;
            }
        }

        // Check special placeholder versions
        if (finalComponents.isEmpty()) {
            var matcher = futureUpdatePattern.matcher(raw);
            if (matcher.matches()) {
                finalComponents.add(matcher.group(1));
                // Group 4 is the opt field
                if (matcher.group(4) != null) {
                    finalComponents.add(null);
                    finalComponents.add(matcher.group(4));
                }
            }
        }

        // Check for team/project special version
        if (finalComponents.isEmpty()) {
            var matcher = projectRepoPattern.matcher(raw);
            if (matcher.matches()) {
                finalComponents.add(matcher.group(1));
                finalComponents.add(matcher.group(2));
            }
        }

        // If no legacy match, use the JEP322 scheme
        if (finalComponents.isEmpty()) {
            // The input strings here never contain a $PRE string, but the $OPT string
            // may contain '-' so matching on first '-' is necessary.
            var optionalStart = raw.indexOf("-");
            String optional = null;
            if (optionalStart >= 0) {
                optional = raw.substring(optionalStart + 1);
                raw = raw.substring(0, optionalStart);
            }
            String prefix = null;
            if ("cpu".equals(optional) && raw.matches("[a-z]+")) {
                // Special case of *-cpu. This symbolic version has no set numbers
                finalComponents.add(raw);
            } else {
                var prefixMatcher = prefixPattern.matcher(raw);
                if (prefixMatcher.matches()) {
                    prefix = prefixMatcher.group(1);
                    raw = prefixMatcher.group(2);
                }

                finalComponents.addAll(Arrays.asList(raw.split("\\.")));

                // All components except the optional one must be numeric
                finalComponents.forEach(Integer::parseUnsignedInt);

                if (prefix != null) {
                    finalComponents.set(0, prefix + finalComponents.get(0));
                }
            }

            if (optional != null) {
                finalComponents.add(null);
                finalComponents.add(optional);
            }
        }

        return finalComponents;
    }

    private JdkVersion(String raw, String build) {
        this.raw = raw;
        this.build = build;

        var rawComponents = splitComponents(raw);
        components = rawComponents.stream()
                                  .takeWhile(Objects::nonNull)
                                  .collect(Collectors.toList());
        opt = rawComponents.stream()
                           .dropWhile(Objects::nonNull)
                           .filter(Objects::nonNull)
                           .findAny().orElse(null);
    }

    public static Optional<JdkVersion> parse(String raw) {
        try {
            return Optional.of(new JdkVersion(raw, null));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<JdkVersion> parse(String raw, String build) {
        try {
            return Optional.of(new JdkVersion(raw, build));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public List<String> components() {
        return new ArrayList<>(components);
    }

    // JEP-322
    public String feature() {
        return components.get(0);
    }

    public Optional<String> interim() {
        if (components.size() > 1) {
            return Optional.of(components.get(1));
        } else {
            return Optional.empty();
        }
    }

    public Optional<String> update() {
        if (components.size() > 2) {
            return Optional.of(components.get(2));
        } else {
            return Optional.empty();
        }
    }

    public Optional<String> patch() {
        if (components.size() > 3) {
            return Optional.of(components.get(3));
        } else {
            return Optional.empty();
        }
    }

    public Optional<String> opt() {
        return Optional.ofNullable(opt);
    }

    public Optional<String> resolvedInBuild() {
        return Optional.ofNullable(build);
    }

    public String raw() {
        return raw;
    }

    // Return the number from a numbered build (e.g., 'b12' -> 12), or -1 if not a numbered build.
    public int resolvedInBuildNumber() {
        if (build == null || build.length() < 2 || build.charAt(0) != 'b') {
            return -1;
        } else {
            return Integer.parseInt(build.substring(1));
        }
    }

    private String legacyFeaturePrefix() {
        var legacyPrefixMatcher = legacyPrefixPattern.matcher(feature());
        if (legacyPrefixMatcher.matches()) {
            return legacyPrefixMatcher.group(1);
        } else {
            return "";
        }
    }

    @Override
    public int compareTo(JdkVersion o) {
        // Filter out the legacy prefix (if they are the same) to enable numerical comparison
        var prefix = legacyFeaturePrefix();
        var otherPrefix = o.legacyFeaturePrefix();

        var myComponents = new ArrayList<>(components);
        var otherComponents = new ArrayList<>(o.components);
        if (!prefix.isBlank() && prefix.equals(otherPrefix)) {
            myComponents.set(0, myComponents.get(0).substring(prefix.length()));
            otherComponents.set(0, otherComponents.get(0).substring(prefix.length()));
        }

        // Compare element by element, numerically if possible
        for (int i = 0; i < Math.min(myComponents.size(), otherComponents.size()); ++i) {
            var elementComparison = 0;
            var myComponent = myComponents.get(i);
            var otherComponent = otherComponents.get(i);
            try {
                elementComparison = Integer.compare(Integer.parseInt(myComponent), Integer.parseInt(otherComponent));
            } catch (NumberFormatException e) {
                elementComparison = myComponent.compareTo(otherComponent);
            }
            if (elementComparison != 0) {
                return elementComparison;
            }
        }

        // A version with additional components comes after an otherwise identical one (12.1.1 > 12.1)
        var sizeDiff = Integer.compare(myComponents.size(), otherComponents.size());
        if (sizeDiff != 0) {
            return sizeDiff;
        }

        // Finally, check the opt part
        if (opt != null) {
            if (o.opt == null) {
                return 1;
            } else {
                return opt.compareTo(o.opt);
            }
        } else {
            if (o.opt == null) {
                return 0;
            } else {
                return -1;
            }
        }
    }

    @Override
    public String toString() {
        return "Version{" +
                "raw='" + raw + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JdkVersion jdkVersion = (JdkVersion) o;
        return raw.equals(jdkVersion.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw);
    }
}
