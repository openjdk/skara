/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.ci;

import org.openjdk.skara.host.Host;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.forge.PullRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

public interface ContinuousIntegration extends Host {
    Job submit(Path source, List<String> jobs, String id) throws IOException;
    Job job(String id) throws IOException;
    List<Job> jobsFor(PullRequest pr) throws IOException;
    void cancel(String id) throws IOException;

    static Optional<ContinuousIntegration> from(URI uri, JSONObject configuration) {
        for (var factory : ContinuousIntegrationFactory.factories()) {
            var ci = factory.create(uri, configuration);
            if (ci.isValid()) {
                return Optional.of(ci);
            }
        }
        return Optional.empty();
    }

    static Optional<ContinuousIntegration> from(URI uri) {
        return from(uri, JSON.object());
    }
}
