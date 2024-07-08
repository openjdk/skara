/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.nio.file.Path;
import java.util.*;

public class WhitespaceIssue extends CommitIssue {
    public static enum Whitespace {
        TAB,
        CR,
        TRAILING;

        @Override
        public String toString() {
            switch (this) {
                case TAB:
                    return "tab";
                case CR:
                    return "carriage return (^M)";
                case TRAILING:
                    return "trailing whitespace";
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    public static class Error {
        private final int index;
        private final Whitespace kind;

        public Error(int index, Whitespace kind) {
            this.index = index;
            this.kind = kind;
        }

        public int index() {
            return index;
        }

        public Whitespace kind() {
            return kind;
        }

        @Override
        public String toString() {
            return index + ": " + kind.toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, kind);
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof Error o)) {
                return false;
            }

            return Objects.equals(index, o.index) &&
                   Objects.equals(kind, o.kind);
        }
    }

    private final Path path;
    private final String line;
    private final int row;
    private final List<Error> errors;

    WhitespaceIssue(Path path, String line, int row, List<Error> errors, CommitIssue.Metadata metadata) {
        super(metadata);
        this.path = path;
        this.line = line;
        this.row = row;
        this.errors = errors;
    }

    public Path path() {
        return path;
    }

    public String line() {
        return line;
    }

    public int row() {
        return row;
    }

    public List<Error> errors() {
        return errors;
    }

    @Override
    public void accept(IssueVisitor v) {
        v.visit(this);
    }

    static Error tab(int index) {
        return new Error(index, Whitespace.TAB);
    }

    static Error cr(int index) {
        return new Error(index, Whitespace.CR);
    }

    static Error trailing(int index) {
        return new Error(index, Whitespace.TRAILING);
    }

    private String join(List<String> words) {
        switch (words.size()) {
            case 0:
                return "";
            case 1:
                return words.get(0);
            case 2:
                return words.get(0) + " and " + words.get(1);
            default:
                var commaSeparated = String.join(", ", words.subList(0, words.size() - 1));
                return commaSeparated + " and " + words.get(words.size() - 1);
        }
    }

    public String describe() {
        int[] counts = new int[3];
        for (var error : errors) {
            if (error.kind() == Whitespace.TAB) {
                counts[0]++;
            } else if (error.kind() == Whitespace.CR) {
                counts[1]++;
            } else {
                counts[2]++;
            }
        }

        var description = new ArrayList<String>();
        if (counts[0] == 1) {
            description.add("tab");
        } else if (counts[0] > 1) {
            description.add("tabs");
        }

        if (counts[1] == 1) {
            description.add("carriage return (^M)");
        } else if (counts[1] > 1) {
            description.add("carriage returns (^M)");
        }

        if (counts[2] > 0) {
            description.add("trailing whitespace");
        }

        return join(description);
    }

    public String escapeLine() {
        return line.replaceAll("\\t", "    ").replaceAll("\\r", "^M");
    }

    public String hints() {
        var hints = new StringBuilder();
        var trailing = true;
        for (var i = line.length() - 1; i >= 0; i--) {
            var c = line.charAt(i);
            if (c == ' ' && trailing) {
                hints.append("^");
            } else if (c == '\t') {
                hints.append("^^^^"); // tab is escaped to 4 chars
            } else if (c == '\r') {
                hints.append("^^");   // cr is escaped to 2 chars
            } else {
                trailing = false;
                hints.append(" ");
            }
        }

        return hints.reverse().toString();
    }
}
