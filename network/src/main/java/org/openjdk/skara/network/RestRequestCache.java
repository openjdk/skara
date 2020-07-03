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
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.*;
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
    private final Map<String, Lock> authLocks = new HashMap<>();
    private final Map<String, Instant> lastUpdates = new ConcurrentHashMap<>();

    HttpResponse<String> send(String authId, HttpRequest.Builder requestBuilder) throws IOException, InterruptedException {
        if (authId == null) {
            authId = "anonymous";
        }
        var unauthenticatedRequest = requestBuilder.build();
        var requestContext = new RequestContext(authId, unauthenticatedRequest);
        synchronized (authLocks) {
            if (!authLocks.containsKey(authId)) {
                authLocks.put(authId, new ReentrantLock());
            }
        }
        var authLock = authLocks.get(authId);
        if (unauthenticatedRequest.method().equals("GET")) {
            var cached = cachedResponses.get(requestContext);
            if (cached != null) {
                var tag = cached.headers().firstValue("ETag");
                tag.ifPresent(value -> requestBuilder.header("If-None-Match", value));
            }
            var finalRequest = requestBuilder.build();
            HttpResponse<String> response;
            try {
                // Perform requests using a certain account serially
                authLock.lock();
                response = client.send(finalRequest, HttpResponse.BodyHandlers.ofString());
            } finally {
                authLock.unlock();
            }
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
            Instant lastUpdate;
            try {
                authLock.lock();
                lastUpdate = lastUpdates.getOrDefault(authId, Instant.now().minus(Duration.ofDays(1)));
                lastUpdates.put(authId, Instant.now());
            } finally {
                authLock.unlock();
            }
            // Perform at most one update per second
            var requiredDelay = Duration.between(Instant.now().minus(Duration.ofSeconds(1)), lastUpdate);
            if (!requiredDelay.isNegative()) {
                try {
                    Thread.sleep(requiredDelay.toMillis());
                } catch (InterruptedException ignored) {
                }
            }
            try {
                authLock.lock();
                return client.send(finalRequest, HttpResponse.BodyHandlers.ofString());
            } finally {
                authLock.unlock();
            }
        }
    }
}
