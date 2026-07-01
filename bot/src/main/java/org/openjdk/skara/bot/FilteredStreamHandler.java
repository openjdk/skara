/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.StreamHandler;
import java.util.regex.Pattern;

public class FilteredStreamHandler extends StreamHandler {
    private final List<RegexReplacement> regexReplacements = new ArrayList<>();

    public void addReplacement(String pattern, String replacement) {
        addReplacement(new RegexReplacement(pattern, replacement));
    }

    public void addReplacement(RegexReplacement regexReplacement) {
        regexReplacements.add(regexReplacement);
    }

    protected String applyReplacements(String s) {
        CharSequence ret = s;
        for (RegexReplacement regexReplacement : regexReplacements) {
            var matcher = regexReplacement.pattern.matcher(ret);
            var sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb, regexReplacement.replacement);
            }
            matcher.appendTail(sb);
            ret = sb;
        }
        return ret.toString();
    }

    public record RegexReplacement(Pattern pattern, String replacement) {
        public RegexReplacement(String pattern, String replacement) {
            this(Pattern.compile(pattern), replacement);
        }
    }
}
