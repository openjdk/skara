/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.args;

import java.util.*;
import java.util.stream.Collectors;

public class Arguments {
    private final List<String> positionals;
    private final Map<String, FlagValue> names = new HashMap<String, FlagValue>();

    public Arguments(List<FlagValue> flags, List<String> positionals) {
        this.positionals = positionals;

        for (var flag : flags) {
            if (flag.fullname() != null) {
                names.put(flag.fullname(), flag);
            }
            if (flag.shortcut() != null) {
                names.put(flag.shortcut(), flag);
            }
        }
    }

    public List<Argument> inputs() {
        return positionals.stream()
                          .map(Argument::new)
                          .collect(Collectors.toList());
    }

    public Argument at(int pos) {
        if (pos < positionals.size()) {
            return new Argument(positionals.get(pos));
        } else {
            return new Argument();
        }
    }

    public Argument get(String name) {
        if (names.containsKey(name)) {
            return new Argument(names.get(name).value());
        }

        return new Argument();
    }

    public boolean contains(String name) {
        return names.containsKey(name);
    }
}
