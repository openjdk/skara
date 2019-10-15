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

import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thrown when invalid URIs are detected in the builder
 */
class URIBuilderException extends RuntimeException {
    URIBuilderException() {

    }

    URIBuilderException(Throwable cause) {
        addSuppressed(cause);
    }
}

public class URIBuilder {

    private static class URIParts {
        String scheme;
        String host;
        String path;
        String userInfo;
        int port;
        String query;
        String fragment;

        URIParts(URI uri) {
            var uriString = uri.toString();
            scheme = uri.getScheme();
            host = uri.getHost();
            var pathStart = host != null ? uriString.indexOf(host) + host.length() : scheme.length() + 3;
            if (uri.getPort() != -1) {
                pathStart += Integer.toString(uri.getPort()).length() + 1;
            }
            var pathEnd = uriString.indexOf("?", pathStart);
            if (pathEnd == -1) {
                pathEnd = uriString.indexOf("#", pathStart);
            }
            if (pathEnd != -1) {
                path = uriString.substring(pathStart, pathEnd);
            } else {
                path = uriString.substring(pathStart);
            }
            userInfo = uri.getUserInfo();
            port = uri.getPort();
            query = uri.getQuery();
            fragment = uri.getFragment();
        }

        URI assemble() throws URISyntaxException {
            // Cannot use the standard URI constructor, as parts of the path may
            // contain escaped slashes (which would then become doubly escaped).
            return new URI((scheme == null ? "http" : scheme) +
                    "://" +
                    (userInfo == null ? "" : userInfo + "@") +
                    host +
                    (port == -1 ? "" : ":" + port) +
                    path +
                    (query == null ? "" : "?" + query) +
                    (fragment == null ? "" : "#" + fragment));
        }
    }

    private URIParts current;

    private URIBuilder(URIParts base) {
        current = base;
    }

    public static URIBuilder base(String base) {
        try {
            var baseUri = new URI(base);
            return new URIBuilder(new URIParts(baseUri));
        } catch (URISyntaxException e) {
            throw new URIBuilderException(e);
        }
    }

    public static URIBuilder base(URI baseUri) {
        return new URIBuilder(new URIParts(baseUri));
    }

    /**
     * Resets the current path to the given one.
     * @param path
     * @return
     */
    public URIBuilder setPath(String path) {
        current.path = path;
        return this;
    }

    /**
     * Adds the given path to the current one.
     * @param path
     * @return
     */
    public URIBuilder appendPath(String path) {
        current.path = current.path + path;
        return this;
    }

    public URIBuilder appendSubDomain(String domain) {
        current.host = domain + "." + current.host;
        return this;
    }

    public URIBuilder setAuthentication(String authentication) {
        current.userInfo = authentication;
        return this;
    }

    public URIBuilder setQuery(Map<String, String> parameters) {
        var query = parameters.entrySet().stream()
                .map(p -> {
                    try {
                        return URLEncoder.encode(p.getKey(), "UTF-8") + "=" + URLEncoder.encode(p.getValue(), "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException("Cannot find UTF-8");
                    }
                })
                .collect(Collectors.joining("&"));

        current.query = query;
        return this;
    }

    /**
     * Returns the final constructed URI.
     * @return
     */
    public URI build() {
        try {
            return current.assemble();
        } catch (URISyntaxException e) {
            throw new URIBuilderException(e);
        }
    }
}
