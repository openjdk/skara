/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.util.logging.Level;
import org.openjdk.skara.metrics.Counter;
import org.openjdk.skara.metrics.Gauge;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.logging.Logger;

enum RestRequestCache {
    INSTANCE;

    private final static Gauge cachedEntriesGauge = Gauge.name("skara_response_cache_size").register();
    private final static Counter cacheHitsCounter = Counter.name("skara_response_cache_hits").register();

    private static class RequestContext {
        private final String authId;
        private final HttpRequest unauthenticatedRequest;
        private final Instant created;

        private RequestContext(String authId, HttpRequest unauthenticatedRequest) {
            this.authId = authId;
            this.unauthenticatedRequest = unauthenticatedRequest;
            created = Instant.now();
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

        public Duration age() {
            return Duration.between(created, Instant.now());
        }
    }

    private class CacheEntry {
        private final HttpResponse<String> response;
        private final Instant callTime;

        public CacheEntry(HttpResponse<String> response, Instant callTime) {
            this.response = response;
            this.callTime = callTime;
        }
    }

    private final Map<RequestContext, CacheEntry> cachedResponses = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Logger log = Logger.getLogger("org.openjdk.skara.network");
    private final ConcurrentHashMap<String, Lock> authLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lock> authNonGetLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastUpdates = new ConcurrentHashMap<>();

    private static class LockWithTimeout implements AutoCloseable {
        private final Lock lock;

        LockWithTimeout(Lock lock) {
            this.lock = lock;
            while (true) {
                try {
                    var locked = lock.tryLock(10, TimeUnit.MINUTES);
                    if (!locked) {
                        throw new RuntimeException("Unable to grab lock in 10 minutes");
                    }
                    return;
                } catch (InterruptedException ignored) {
                }
            }
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private static class CachedHttpResponse<T> implements HttpResponse<T> {
        private final HttpResponse<T> original;
        private final HttpResponse<T> fromRequest;

        CachedHttpResponse(HttpResponse<T> original, HttpResponse<T> fromRequest) {
            this.original = original;
            this.fromRequest = fromRequest;
        }

        @Override
        public int statusCode() {
            return original.statusCode();
        }

        @Override
        public HttpRequest request() {
            return fromRequest.request();
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return fromRequest.previousResponse();
        }

        @Override
        public HttpHeaders headers() {
            var combined = new HashMap<String, List<String>>();
            for (var header : original.headers().map().entrySet()) {
                combined.put(header.getKey(), header.getValue());
            }
            for (var header : fromRequest.headers().map().entrySet()) {
                combined.put(header.getKey(), header.getValue());
            }
            return HttpHeaders.of(combined, (a, b) -> true);
        }

        @Override
        public T body() {
            return original.body();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return fromRequest.sslSession();
        }

        @Override
        public URI uri() {
            return fromRequest.uri();
        }

        @Override
        public HttpClient.Version version() {
            return fromRequest.version();
        }
    }

    private Duration maxAllowedAge(RequestContext requestContext) {
        // Known stable caches can afford a longer timeout - others expire faster
        if (requestContext.unauthenticatedRequest.uri().toString().contains("github.com")) {
            return Duration.ofMinutes(30);
        } else {
            return Duration.ofMinutes(5);
        }
    }

    HttpResponse<String> send(String authId, HttpRequest.Builder requestBuilder, boolean skipLimiter) throws IOException, InterruptedException {
        if (authId == null) {
            authId = "anonymous";
        }
        var unauthenticatedRequest = requestBuilder.build();
        var requestContext = new RequestContext(authId, unauthenticatedRequest);
        var authLock = authLocks.computeIfAbsent(authId, id -> new ReentrantLock());
        if (unauthenticatedRequest.method().equals("GET") || skipLimiter) {
            var cached = cachedResponses.get(requestContext);
            if (cached != null) {
                if (Instant.now().minus(maxAllowedAge(requestContext)).isBefore(cached.callTime)) {
                    var tag = cached.response.headers().firstValue("ETag");
                    tag.ifPresent(value -> requestBuilder.header("If-None-Match", value));
                } else {
                    log.finer("Expired response cache for " + requestContext.unauthenticatedRequest.uri() + " (" + requestContext.authId + ")");
                }
            }
            var finalRequest = requestBuilder.build();
            HttpResponse<String> response;
            var beforeLock = Instant.now();
            try (var ignored = new LockWithTimeout(authLock)) {
                // Perform requests using a certain account serially
                var beforeCall = Instant.now();
                var lockDelay = Duration.between(beforeLock, beforeCall);
                log.log(Level.FINE, "Taking lock for " + finalRequest.method() + " " + finalRequest.uri() + " took " + lockDelay, lockDelay);
                response = client.send(finalRequest, HttpResponse.BodyHandlers.ofString());
                var callDuration = Duration.between(beforeCall, Instant.now());
                log.log(Level.FINE, "Calling " + finalRequest.method() + " " + finalRequest.uri().toString() + " took " + callDuration, callDuration);
            }
            if (cached != null && response.statusCode() == 304) {
                cacheHitsCounter.inc();
                log.finer("Using cached response for " + finalRequest + " (" + authId + ")");
                return new CachedHttpResponse<>(cached.response, response);
            } else {
                cachedResponses.put(requestContext, new CacheEntry(response, Instant.now()));
                cachedEntriesGauge.set(cachedResponses.size());
                log.finer("Updating response cache for " + finalRequest + " (" + authId + ")");
                return response;
            }
        } else {
            var authNonGetLock = authNonGetLocks.computeIfAbsent(authId, id -> new ReentrantLock());
            var finalRequest = requestBuilder.build();
            log.finer("Not using response cache for " + finalRequest + " (" + authId + ")");
            Instant lastUpdate;
            var beforeLock = Instant.now();
            try (var ignored = new LockWithTimeout(authNonGetLock)) {
                // Perform at most one update per second
                lastUpdate = lastUpdates.getOrDefault(authId, Instant.now().minus(Duration.ofDays(1)));
                var requiredDelay = Duration.between(Instant.now().minus(Duration.ofSeconds(1)), lastUpdate);
                if (!requiredDelay.isNegative()) {
                    try {
                        Thread.sleep(requiredDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                try (var ignored2 = new LockWithTimeout(authLock)) {
                    var beforeCall = Instant.now();
                    lastUpdates.put(authId, beforeCall);
                    var lockDelay = Duration.between(beforeLock, beforeCall);
                    log.log(Level.FINE, "Taking lock and adding required delay for " + finalRequest.method() + " " + finalRequest.uri() + " took " + lockDelay, lockDelay);
                    var response = client.send(finalRequest, HttpResponse.BodyHandlers.ofString());
                    var callDuration = Duration.between(beforeCall, Instant.now());
                    log.log(Level.FINE, "Calling " + finalRequest.method() + " " + finalRequest.uri().toString() + " took " + callDuration, callDuration);
                    return response;
                }
            } finally {
                // Invalidate any related GET caches
                var postUriString = unauthenticatedRequest.uri().toString();
                var iterator = cachedResponses.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getKey().unauthenticatedRequest.uri().toString().startsWith(postUriString)) {
                        iterator.remove();
                        cachedEntriesGauge.set(cachedResponses.size());
                    }
                }
            }
        }
    }

    /**
     * This method should be run from time to time to keep the cache from growing indefinitely.
     */
    public void evictOldData() {
        var now = Instant.now();
        var iterator = cachedResponses.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().callTime.isBefore(now.minus(maxAllowedAge(entry.getKey())))) {
                iterator.remove();
                cachedEntriesGauge.set(cachedResponses.size());
            }
        }
    }
}
