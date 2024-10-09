/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.issuetracker;

import java.time.Duration;
import java.net.URI;
import java.util.Optional;

import org.openjdk.skara.host.*;
import org.openjdk.skara.network.RestRequest;
import org.openjdk.skara.json.*;


public interface IssueTracker extends Host {
    public interface CustomEndpointRequest {
        CustomEndpointRequest body(JSONValue json);
        CustomEndpointRequest header(String value, String name);
        CustomEndpointRequest onError(RestRequest.ErrorTransform transform);

        JSONValue execute();
    }

    public interface CustomEndpoint {
        default CustomEndpointRequest post() {
            throw new UnsupportedOperationException("HTTP method POST is not supported");
        }

        default CustomEndpointRequest get() {
            throw new UnsupportedOperationException("HTTP method GET is not supported");
        }

        default CustomEndpointRequest put() {
            throw new UnsupportedOperationException("HTTP method PUT is not supported");
        }

        default CustomEndpointRequest patch() {
            throw new UnsupportedOperationException("HTTP method PATCH is not supported");
        }

        default CustomEndpointRequest delete() {
            throw new UnsupportedOperationException("HTTP method DELETE is not supported");
        }
    }

    /**
     * Creates and caches a new project if it hasn't been initialized.
     * Subsequent calls with the same name will return the cached instance.
     */
    IssueProject project(String name);
    Optional<CustomEndpoint> lookupCustomEndpoint(String path);
    URI uri();

    static IssueTracker from(String name, URI uri, Credential credential, JSONObject configuration) {
        var factory = IssueTrackerFactory.getIssueTrackerFactories().stream()
                                  .filter(f -> f.name().equals(name))
                                  .findFirst();
        if (factory.isEmpty()) {
            throw new RuntimeException("No issue tracker factory named '" + name + "' found - check module path");
        }
        return factory.get().create(uri, credential, configuration);
    }

    static IssueTracker from(String name, URI uri, Credential credential) {
        return from(name, uri, credential, null);
    }

    static IssueTracker from(String name, URI uri) {
        return from(name, uri, null, null);
    }
}
