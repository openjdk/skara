/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.network;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

enum RestRequestCache {
    INSTANCE;

    private static class RequestContext {
        private final String authId;
        private final HttpRequest unauthenticatedRequest;

        private RequestContext(String authId, HttpRequest unauthenticatedRequest) {
            this.authId = authId;
            this.unauthenticatedRequest = unauthenticatedRequest;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            RequestContext that = (RequestContext) o;
            return Objects.equals(authId, that.authId) && unauthenticatedRequest.equals(that.unauthenticatedRequest);
        }

        @Override
        public int hashCode() {
            return Objects.hash(authId, unauthenticatedRequest);
        }
    }

    private final Map<RequestContext, HttpResponse<String>> cachedResponses = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Logger log = Logger.getLogger("org.openjdk.skara.network");

    HttpResponse<String> send(String authId, HttpRequest.Builder requestBuilder) throws IOException, InterruptedException {
        var unauthenticatedRequest = requestBuilder.build();
        var requestContext = new RequestContext(authId, unauthenticatedRequest);
        if (unauthenticatedRequest.method().equals("GET")) {
            var cached = cachedResponses.get(requestContext);
            if (cached != null) {
                var tag = cached.headers().firstValue("ETag");
                tag.ifPresent(value -> requestBuilder.header("If-None-Match", value));
            }
            var finalRequest = requestBuilder.build();
            var response = client.send(finalRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 304) {
                log.finer("Using cached response for " + finalRequest + " (" + authId + ")");
                return cached;
            } else {
                cachedResponses.put(requestContext, response);
                log.finer("Updating response cache for " + finalRequest + " (" + authId + ")");
                return response;
            }
        } else {
            var finalRequest = requestBuilder.build();
            log.finer("Not using response cache for " + finalRequest + " (" + authId + ")");
            return client.send(finalRequest, HttpResponse.BodyHandlers.ofString());
        }
    }
}
