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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.ci.*;

import java.util.List;
import java.util.ArrayList;

class InMemoryJob implements Job {
    String id = "";
    List<Build> builds = new ArrayList<>();
    List<Test> tests = new ArrayList<>();
    Job.Status status;
    Job.Result result;
    Job.State state;

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<Build> builds() {
        return builds;
    }

    @Override
    public List<Test> tests() {
        return tests;
    }

    @Override
    public Job.Status status() {
        return status;
    }

    @Override
    public Job.Result result() {
        return result;
    }

    @Override
    public Job.State state() {
        return state;
    }
}
