/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.ReadOnlyRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;

import java.util.Iterator;
import java.util.logging.Logger;

public class BranchesCheck extends RepositoryCheck {
    private final Pattern allowed;
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.branches");

    BranchesCheck(Pattern allowed) {
        this.allowed = allowed;
    }

    private boolean isAllowed(Branch b, ReadOnlyRepository repo) {
        try {
            return b.equals(repo.defaultBranch()) || allowed.matcher(b.name()).matches();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    Iterator<Issue> check(ReadOnlyRepository repo) {
        log.finer("Allowed branches: " + allowed.toString());
        try {
            return repo.branches()
                       .stream()
                       .filter(b -> !isAllowed(b, repo))
                       .map(b -> (Issue) new BranchIssue(b, this))
                       .iterator();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String name() {
        return "branches";
    }

    @Override
    public String description() {
        return "Branch names must use correct syntax";
    }
}
