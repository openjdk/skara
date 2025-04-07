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
package org.openjdk.skara.network;

import org.openjdk.skara.json.*;
import org.openjdk.skara.metrics.Counter;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RestRequest {
    private RestRequestCache cache = RestRequestCache.INSTANCE;
    private final static Counter.WithOneLabel requestCounter =
        Counter.name("skara_http_requests")
               .labels("method")
               .register();
    private final static Counter.WithTwoLabels responseCounter =
        Counter.name("skara_http_responses")
               .labels("code", "error_handled")
               .register();

    private enum RequestType {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE
    }

    @FunctionalInterface
    public interface AuthenticationGenerator {
        List<String> getAuthHeaders(HttpRequest.Builder request);
    }

    @FunctionalInterface
    public interface NextLinkExtractor {
        Optional<HttpRequest.Builder> getNextLinkRequest(HttpResponse<String> response);
    }

    @FunctionalInterface
    public interface ErrorTransform {
        Optional<JSONValue> onError(HttpResponse<String> response);
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
        private String rawBody;
        private int maxPages;
        private ErrorTransform onError;
        private String sha256Header;
        private boolean skipLimiter = false;
        private boolean failOnEmptyResponse = false;

        private QueryBuilder(RequestType queryType, String endpoint) {
            this.queryType = queryType;
            this.endpoint = endpoint;

            body = null;
            rawBody = null;
            maxPages = Integer.MAX_VALUE;
            onError = null;
        }

        private String composedBody() {
            if (rawBody != null && (body != null || !bodyParams.isEmpty())) {
                throw new RuntimeException("Cannot mix raw body and JSON body in request");
            }

            if (rawBody != null) {
                return rawBody;
            }

            if (body == null && queryType == RequestType.GET && bodyParams.isEmpty()) {
                return null;
            }

            var finalBody = body == null ? JSON.object() : body.asObject();
            for (var param : bodyParams) {
                finalBody.put(param.key, param.value);
            }
            return finalBody.toString();
        }

        private boolean isJSON() {
            return body != null || !bodyParams.isEmpty();
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
         * Sets the request body encoded as raw POST data.
         * @param data
         * @return
         */
        public QueryBuilder body(String data) {
            rawBody = data;
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

        /**
         * Optionally name a header where a sha256 hash of the contents will be added.
         * This is commonly used by cloud vendors as part of verifying requests.
         */
        public QueryBuilder sha256Header(String name) {
            sha256Header = name;
            return this;
        }

        public QueryBuilder skipLimiter(boolean skipLimiter) {
            this.skipLimiter = skipLimiter;
            return this;
        }

        public QueryBuilder failOnEmptyResponse(boolean failOnEmptyResponse) {
            this.failOnEmptyResponse = failOnEmptyResponse;
            return this;
        }

        public JSONValue execute() {
            try {
                return RestRequest.this.execute(this);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public String executeUnparsed() throws IOException {
            return RestRequest.this.executeUnparsed(this);
        }

        public HttpRequest build() {
            return RestRequest.this.build(this);
        }

        @Override
        public String toString() {
            return "QueryBuilder: type: " + queryType +
                    ", endpoint: " + endpoint +
                    ", body: " + composedBody();
        }
    }

    private final URI apiBase;
    private final String authId;
    private final AuthenticationGenerator authGen;
    private final NextLinkExtractor nextLinkExtractor;
    private final Logger log = Logger.getLogger("org.openjdk.skara.host.network");

    public RestRequest(URI apiBase, String authId, AuthenticationGenerator authGen,
                       NextLinkExtractor nextLinkExtractor) {
        this.apiBase = apiBase;
        this.authId = authId;
        this.authGen = authGen;
        this.nextLinkExtractor = nextLinkExtractor;
    }

    public RestRequest(URI apiBase, String authId, AuthenticationGenerator authGen) {
        this.apiBase = apiBase;
        this.authId = authId;
        this.authGen = authGen;
        this.nextLinkExtractor = this::getNextLinkRequest;
    }

    public RestRequest(URI apiBase) {
        this(apiBase, null, null);
    }

    /**
     * Creates a new request restricted to the given endpoint.
     * @param endpoint
     * @return
     */
    public RestRequest restrict(String endpoint) {
        return new RestRequest(URIBuilder.base(apiBase).appendPath(endpoint).build(), authId, authGen);
    }

    private URIBuilder getEndpointURI(String endpoint) {
        return URIBuilder.base(apiBase)
                         .appendPath(endpoint);
    }

    private HttpRequest.Builder getHttpRequestBuilder(URI uri) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .timeout(Duration.ofSeconds(30));
        return builder;
    }

    private void logRateLimit(HttpHeaders headers) {
        if ((headers.firstValue("x-ratelimit-limit").isEmpty()) ||
                (headers.firstValue("x-ratelimit-remaining").isEmpty()) ||
                (headers.firstValue("x-ratelimit-reset").isEmpty())) {
            return;
        }

        var limit = Integer.parseInt(headers.firstValue("x-ratelimit-limit").get());
        var remaining = Integer.parseInt(headers.firstValue("x-ratelimit-remaining").get());
        var reset = Integer.parseInt(headers.firstValue("x-ratelimit-reset").get());
        var timeToReset = Duration.between(Instant.now(), Instant.ofEpochSecond(reset));

        var level = Level.FINE;
        var remainingPercentage = (remaining * 100) / limit;
        if (remainingPercentage < 10) {
            level = Level.SEVERE;
        } else if (remainingPercentage < 20) {
            level = Level.WARNING;
        } else if (remainingPercentage < 50) {
            level = Level.INFO;
        }
        log.log(level,"Rate limit: " + limit + " Remaining: " + remaining + " (" + remainingPercentage + "%) " +
                "Resets in: " + timeToReset.toMinutes() + " minutes");
    }

    private Duration retryBackoffStep = Duration.ofSeconds(1);

    void setRetryBackoffStep(Duration duration) {
        retryBackoffStep = duration;
    }

    private HttpResponse<String> sendRequest(HttpRequest.Builder request, boolean skipLimiter) throws IOException {
        HttpResponse<String> response;

        var authHeaders = addAuthHeaders(request);

        var retryCount = 0;
        while (true) {
            try {
                response = cache.send(authId, request, skipLimiter);
                // If the status code is 301(Moved Permanently), follow the redirect link
                if (response.statusCode() == 301 && retryCount < 2) {
                    var location = response.headers().firstValue("location");
                    if (location.isPresent()) {
                        request = request.uri(URI.create(location.get()));
                    }
                }
                // If we are using authorization and get a 401, we need to retry to give
                // the authorization mechanism a chance to refresh stale tokens. Retry if
                // we get a new set of authorization headers.
                else if (response.statusCode() == 401 && retryCount < 2 && authHeaders != null
                        && !authHeaders.equals(addAuthHeaders(request))) {
                    log.info("Failed authorization for request: " + request.build().uri()
                            + ", retry count: " + retryCount);
                } else {
                    break;
                }
            } catch (InterruptedException | IOException e) {
                if (retryCount < 5) {
                    try {
                        Thread.sleep(retryBackoffStep.multipliedBy(retryCount));
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    try {
                        throw e;
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
            retryCount++;
        }

        logRateLimit(response.headers());
        return response;
    }

    /**
     * Adds authorization headers to the request builder. Returns the headers that
     * were added or null if no authorization mechanism was defined.
     */
    private List<String> addAuthHeaders(HttpRequest.Builder request) {
        if (authGen != null) {
            var authHeaders = authGen.getAuthHeaders(request);
            for (int i = 0; i < authHeaders.size() - 1; i += 2) {
                String name  = authHeaders.get(i);
                String value = authHeaders.get(i + 1);
                request.setHeader(name, value);
            }
            return authHeaders;
        }
        return null;
    }

    private JSONValue parseResponse(HttpResponse<String> response) {
        if (response.body().isEmpty()) {
            return JSON.of();
        }
        try {
            return JSON.parse(response.body());
        } catch (RuntimeException e) {
            throw new UncheckedRestException("Failed to parse response", e, response.statusCode(), response.request());
        }
    }

    private Optional<JSONValue> transformBadResponse(HttpResponse<String> response, QueryBuilder queryBuilder) {
        if (response.statusCode() >= 400) {
            if (queryBuilder.onError != null) {
                var transformed = queryBuilder.onError.onError(response);
                if (transformed.isPresent()) {
                    return transformed;
                }
            }
            log.warning("Request " + response.request().method() + " " + response.request().uri()
                    + " returned bad status: " + response.statusCode());
            log.info(queryBuilder.toString());
            log.info(response.body());
            throw new UncheckedRestException(response.statusCode(), response.request());
        } else {
            return Optional.empty();
        }
    }

    private HttpRequest.Builder createRequest(RequestType requestType, String endpoint, String body,
                                              List<QueryBuilder.Param> params, Map<String, String> headers,
                                              boolean isJSON, String sha256Header) {
        var uriBuilder = URIBuilder.base(apiBase);
        if (endpoint != null && !endpoint.isEmpty()) {
            uriBuilder = uriBuilder.appendPath(endpoint);
        }
        if (!params.isEmpty()) {
            var query = new LinkedHashMap<String, List<String>>();
            for (var param : params) {
                if (!query.containsKey(param.key)) {
                    query.put(param.key, new ArrayList<String>());
                }
                query.get(param.key).add(param.value);
            }
            uriBuilder.setQuery(query);
        }
        var uri = uriBuilder.build();

        var requestBuilder = HttpRequest.newBuilder()
                                        .uri(uri)
                                        .timeout(Duration.ofSeconds(30));

        if (isJSON) {
            requestBuilder = requestBuilder.header("Content-type", "application/json");
        }

        if (body != null) {
            requestBuilder.method(requestType.name(), HttpRequest.BodyPublishers.ofString(body));
            if (sha256Header != null) {
                try {
                    var digest = MessageDigest.getInstance("SHA-256");
                    var hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
                    var encoded  = new String(Base64.getEncoder().encode(hash), StandardCharsets.UTF_8);
                    requestBuilder.header(sha256Header, encoded);
                } catch (NoSuchAlgorithmException e) {
                    throw new Error("SHA-256 algorithm not found");
                }
            }
        }
        headers.forEach(requestBuilder::header);
        return requestBuilder;
    }

    private final Pattern linkPattern = Pattern.compile("<(.*?)>; rel=\"(.*?)\"");

    private Map<String, String> parseLink(String link) {
        return linkPattern.matcher(link).results()
                          .collect(Collectors.toMap(m -> m.group(2), m -> m.group(1)));
    }

    private JSONValue combinePages(List<JSONValue> pages) {
        if (pages.get(0).isArray()) {
            return new JSONArray(pages.stream()
                                      .map(JSONValue::asArray)
                                      .flatMap(JSONArray::stream)
                                      .toArray(JSONValue[]::new));
        } else {
            // Find the largest array - that should be the paginated one
            JSONValue paginated = null;
            for (var field : pages.get(0).fields()) {
                if (field.value().isArray()) {
                    if ((paginated == null) || field.value().asArray().size() > paginated.asArray().size()) {
                        paginated = field.value();
                    }
                }
            }

            var ret = JSON.object();
            for (var field : pages.get(0).fields()) {
                if (field.value() == paginated) {
                    var combined = new JSONArray(pages.stream()
                                                      .map(page -> page.get(field.name()).asArray())
                                                      .flatMap(JSONArray::stream)
                                                      .toArray(JSONValue[]::new));
                    ret.put(field.name(), combined);
                } else {
                    ret.put(field.name(), field.value());
                }
            }
            return ret;
        }
    }

    private Optional<HttpRequest.Builder> getNextLinkRequest(HttpResponse<String> response) {
        var link = response.headers().firstValue("Link");
        if (link.isEmpty()) {
            return Optional.empty();
        }
        var links = parseLink(link.get());
        if (!links.containsKey("next")) {
            return Optional.empty();
        }
        var uri = URI.create(links.get("next"));
        return Optional.of(getHttpRequestBuilder(uri).GET());
    }

    private HttpRequest build(QueryBuilder queryBuilder) {
        var request = createRequest(queryBuilder.queryType, queryBuilder.endpoint, queryBuilder.composedBody(),
                queryBuilder.params, queryBuilder.headers, queryBuilder.isJSON(), queryBuilder.sha256Header);
        return request.build();
    }

    private JSONValue execute(QueryBuilder queryBuilder) throws IOException {
        var request = createRequest(queryBuilder.queryType, queryBuilder.endpoint, queryBuilder.composedBody(),
                queryBuilder.params, queryBuilder.headers, queryBuilder.isJSON(), queryBuilder.sha256Header);
        requestCounter.labels(queryBuilder.queryType.toString()).inc();
        var response = sendRequest(request, queryBuilder.skipLimiter);
        var errorTransform = transformBadResponse(response, queryBuilder);
        responseCounter.labels(Integer.toString(response.statusCode()), Boolean.toString(errorTransform.isPresent())).inc();
        if (errorTransform.isPresent()) {
            return errorTransform.get();
        }

        if (queryBuilder.failOnEmptyResponse && response.body().isBlank()) {
            throw new UncheckedRestException("Empty response body"
                    + response.headers().firstValue("Location").map(s -> ", redirect: " + s).orElse(""),
                    response.statusCode(), request.build());
        }

        var nextRequest = nextLinkExtractor.getNextLinkRequest(response);
        if (nextRequest.isEmpty() || queryBuilder.maxPages < 2) {
            return parseResponse(response);
        }

        // If a pagination header is present, we have to collect all responses
        var ret = new LinkedList<JSONValue>();
        var parsedResponse = parseResponse(response);
        ret.add(parsedResponse);

        while (nextRequest.isPresent() && ret.size() < queryBuilder.maxPages) {
            requestCounter.labels(queryBuilder.queryType.toString()).inc();
            response = sendRequest(nextRequest.get(), queryBuilder.skipLimiter);

            // If an error occurs during paginated parsing, we have to discard all previous data
            errorTransform = transformBadResponse(response, queryBuilder);
            responseCounter.labels(Integer.toString(response.statusCode()), Boolean.toString(errorTransform.isPresent())).inc();
            if (errorTransform.isPresent()) {
                return errorTransform.get();
            }

            nextRequest = nextLinkExtractor.getNextLinkRequest(response);

            parsedResponse = parseResponse(response);
            ret.add(parsedResponse);
        }
        return combinePages(ret);
    }

    private String executeUnparsed(QueryBuilder queryBuilder) throws IOException {
        var request = createRequest(queryBuilder.queryType, queryBuilder.endpoint, queryBuilder.composedBody(),
                queryBuilder.params, queryBuilder.headers, queryBuilder.isJSON(), queryBuilder.sha256Header);
        requestCounter.labels(queryBuilder.queryType.toString()).inc();
        var response = sendRequest(request, queryBuilder.skipLimiter);
        responseCounter.labels(Integer.toString(response.statusCode()), Boolean.toString(false)).inc();
        if (response.statusCode() >= 400) {
            var responseJSONValue = JSON.parse(response.body());
            if (responseJSONValue.contains("message")) {
                throw new UncheckedRestException(responseJSONValue.get("message").asString(), response.statusCode(), response.request());
            } else {
                throw new UncheckedRestException(response.statusCode(), response.request());
            }
        }
        return response.body();
    }

    public QueryBuilder get(String endpoint) {
        return new QueryBuilder(RequestType.GET, endpoint).failOnEmptyResponse(true);
    }

    public QueryBuilder get() {
        return get(null).failOnEmptyResponse(true);
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

    public static void evictOldCacheData() {
        RestRequestCache.INSTANCE.evictOldData();
    }
}
