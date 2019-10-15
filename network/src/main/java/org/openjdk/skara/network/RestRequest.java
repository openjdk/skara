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
package org.openjdk.skara.network;

import org.openjdk.skara.json.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RestRequest {
    private enum RequestType {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE
    }

    @FunctionalInterface
    public interface AuthenticationGenerator {
        List<String> getAuthHeaders();
    }

    @FunctionalInterface
    public interface ErrorTransform {
        JSONValue onError(HttpResponse<String> response);
    }

    public class QueryBuilder {
        private class Param {
            String key;
            String value;
        }

        private final RequestType queryType;
        private final String endpoint;

        private final List<Param> params = new ArrayList<>();
        private final List<Param> bodyParams = new ArrayList<>();
        private final Map<String, String> headers = new HashMap<>();
        private JSONValue body;
        private int maxPages;
        private ErrorTransform onError;

        private QueryBuilder(RequestType queryType, String endpoint) {
            this.queryType = queryType;
            this.endpoint = endpoint;

            body = null;
            maxPages = Integer.MAX_VALUE;
            onError = null;
        }

        private JSONValue composedBody() {
            var finalBody = body == null ? JSON.object() : body.asObject();
            for (var param : bodyParams) {
                finalBody.put(param.key, param.value);
            }
            return finalBody;
        }

        /**
         * Pass a parameter through the url query mechanism.
         * @param key
         * @param value
         * @return
         */
        public QueryBuilder param(String key, String value) {
            var param = new Param();
            param.key = key;
            param.value = value;
            params.add(param);
            return this;
        }

        /**
         * Adds a body parameter that will be encoded in a json key-value structure.
         * @param key
         * @param value
         * @return
         */
        public QueryBuilder body(String key, String value) {
            var param = new Param();
            param.key = key;
            param.value = value;
            bodyParams.add(param);
            return this;
        }

        /**
         * Sets the request body encoded as json.
         * @param json
         * @return
         */
        public QueryBuilder body(JSONValue json) {
            body = json;
            return this;
        }

        /**
         * When parsing paginated results, stop after this number of pages
         * @param count 0 means all
         * @return
         */
        public QueryBuilder maxPages(int count) {
            maxPages = count;
            return this;
        }

        /**
         * If an http error code is returned, apply the given function to the response to obtain a valid
         * return value instead of throwing an exception.
         * @param errorTransform
         * @return
         */
        public QueryBuilder onError(ErrorTransform errorTransform) {
            onError = errorTransform;
            return this;
        }

        public QueryBuilder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public JSONValue execute() {
            return RestRequest.this.execute(this);
        }

        public String executeUnparsed() {
            return RestRequest.this.executeUnparsed(this);
        }
    }

    private final URI apiBase;
    private final AuthenticationGenerator authGen;
    private final Logger log = Logger.getLogger("org.openjdk.skara.host.network");

    public RestRequest(URI apiBase, AuthenticationGenerator authGen) {
        this.apiBase = apiBase;
        this.authGen = authGen;
    }

    public RestRequest(URI apiBase) {
        this.apiBase = apiBase;
        this.authGen = null;
    }

    /**
     * Creates a new request restricted to the given endpoint.
     * @param endpoint
     * @return
     */
    public RestRequest restrict(String endpoint) {
        return new RestRequest(URIBuilder.base(apiBase).appendPath(endpoint).build(), authGen);
    }

    private URIBuilder getEndpointURI(String endpoint) {
        return URIBuilder.base(apiBase)
                         .appendPath(endpoint);
    }

    private HttpRequest.Builder getHttpRequestBuilder(URI uri) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .timeout(Duration.ofSeconds(30));
        if (authGen != null) {
            builder.headers(authGen.getAuthHeaders().toArray(new String[0]));
        }
        return builder;
    }

    private void logRateLimit(HttpHeaders headers) {
        if ((!headers.firstValue("x-ratelimit-limit").isPresent()) ||
                (!headers.firstValue("x-ratelimit-remaining").isPresent()) ||
                (!headers.firstValue("x-ratelimit-reset").isPresent())) {
            return;
        }

        var limit = Integer.valueOf(headers.firstValue("x-ratelimit-limit").get());
        var remaining = Integer.valueOf(headers.firstValue("x-ratelimit-remaining").get());
        var reset = Integer.valueOf(headers.firstValue("x-ratelimit-reset").get());

        log.fine("Rate limit: " + limit + " - remaining: " + remaining);
    }

    private Duration retryBackoffStep = Duration.ofSeconds(1);

    void setRetryBackoffStep(Duration duration) {
        retryBackoffStep = duration;
    }

    private HttpResponse<String> sendRequest(HttpRequest request) {
        HttpResponse<String> response;

        var retryCount = 0;
        while (true) {
            try {
                var client = HttpClient.newBuilder()
                                       .connectTimeout(Duration.ofSeconds(10))
                                       .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (IOException | InterruptedException e) {
                if (retryCount < 5) {
                    try {
                        Thread.sleep(retryCount * retryBackoffStep.toMillis());
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    throw new RuntimeException(e);
                }
            }
            retryCount++;
        }

        logRateLimit(response.headers());
        return response;
    }

    private JSONValue parseResponse(HttpResponse<String> response) {
        if (response.body().isEmpty()) {
            return JSON.of();
        }
        return JSON.parse(response.body());
    }

    private Optional<JSONValue> transformBadResponse(HttpResponse<String> response, QueryBuilder queryBuilder) {
        if (response.statusCode() >= 400) {
            if (queryBuilder.onError == null) {
                log.warning(queryBuilder.toString());
                log.warning(response.body());
                throw new RuntimeException("Request returned bad status: " + response.statusCode());
            } else {
                return Optional.of(queryBuilder.onError.onError(response));
            }
        } else {
            return Optional.empty();
        }
    }

    private HttpRequest createRequest(RequestType requestType, String endpoint, JSONValue body,
                                      List<QueryBuilder.Param> params, Map<String, String> headers) {
        var uriBuilder = URIBuilder.base(apiBase);
        if (endpoint != null && !endpoint.isEmpty()) {
            uriBuilder = uriBuilder.appendPath(endpoint);
        }
        if (!params.isEmpty()) {
            uriBuilder.setQuery(params.stream().collect(Collectors.toMap(param -> param.key, param -> param.value)));
        }
        var uri = uriBuilder.build();

        var requestBuilder = HttpRequest.newBuilder()
                                        .uri(uri)
                                        .timeout(Duration.ofSeconds(30))
                                        .header("Content-type", "application/json");
        if (authGen != null) {
            requestBuilder.headers(authGen.getAuthHeaders().toArray(new String[0]));
        }
        if (body != null) {
            requestBuilder.method(requestType.name(), HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        headers.forEach(requestBuilder::header);
        return requestBuilder.build();
    }

    private final Pattern linkPattern = Pattern.compile("<(.*?)>; rel=\"(.*?)\"");

    private Map<String, String> parseLink(String link) {
        return linkPattern.matcher(link).results()
                          .collect(Collectors.toMap(m -> m.group(2), m -> m.group(1)));
    }

    private JSONValue execute(QueryBuilder queryBuilder) {
        var request = createRequest(queryBuilder.queryType, queryBuilder.endpoint, queryBuilder.composedBody(),
                                    queryBuilder.params, queryBuilder.headers);
        var response = sendRequest(request);
        var errorTransform = transformBadResponse(response, queryBuilder);
        if (errorTransform.isPresent()) {
            return errorTransform.get();
        }

        var link = response.headers().firstValue("Link");
        if (link.isEmpty() || queryBuilder.maxPages < 2) {
            return parseResponse(response);
        }

        // If a pagination header is present, it means that the returned data type must be an array
        var ret = new LinkedList<JSONArray>();
        var parsedResponse = parseResponse(response).asArray();
        ret.add(parsedResponse);

        var links = parseLink(link.get());
        while (links.containsKey("next") && ret.size() < queryBuilder.maxPages) {
            var uri = URI.create(links.get("next"));
            request = getHttpRequestBuilder(uri).GET().build();
            response = sendRequest(request);

            // If an error occurs during paginated parsing, we have to discard all previous data
            errorTransform = transformBadResponse(response, queryBuilder);
            if (errorTransform.isPresent()) {
                return errorTransform.get();
            }

            link = response.headers().firstValue("Link");
            links = parseLink(link.orElseThrow(
                    () -> new RuntimeException("Initial paginated response no longer paginated")));

            parsedResponse = parseResponse(response).asArray();
            ret.add(parsedResponse);
        }

        return new JSONArray(ret.stream().flatMap(JSONArray::stream).toArray(JSONValue[]::new));
    }

    private String executeUnparsed(QueryBuilder queryBuilder) {
        var request = createRequest(queryBuilder.queryType, queryBuilder.endpoint, queryBuilder.composedBody(),
                                    queryBuilder.params, queryBuilder.headers);
        var response = sendRequest(request);
        return response.body();
    }

    public QueryBuilder get(String endpoint) {
        return new QueryBuilder(RequestType.GET, endpoint);
    }

    public QueryBuilder get() {
        return get(null);
    }

    public QueryBuilder post(String endpoint) {
        return new QueryBuilder(RequestType.POST, endpoint);
    }

    public QueryBuilder post() {
        return post(null);
    }

    public QueryBuilder put(String endpoint) {
        return new QueryBuilder(RequestType.PUT, endpoint);
    }

    public QueryBuilder put() {
        return put(null);
    }

    public QueryBuilder patch(String endpoint) {
        return new QueryBuilder(RequestType.PATCH, endpoint);
    }

    public QueryBuilder patch() {
        return patch(null);
    }

    public QueryBuilder delete(String endpoint) {
        return new QueryBuilder(RequestType.DELETE, endpoint);
    }

    public QueryBuilder delete() {
        return delete(null);
    }
}
