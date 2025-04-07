/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.net.http.HttpRequest;

/**
 * Specialized RuntimeException thrown when a REST call receives a response code
 * >=400 that isn't handled. When catching this, details about the failed call
 * has already been logged.
 */
public class UncheckedRestException extends RuntimeException {
    private final int statusCode;
    private final HttpRequest request;

    public UncheckedRestException(int statusCode, HttpRequest request) {
        this("Request returned bad status", null, statusCode, request);
    }

    public UncheckedRestException(String message, int statusCode, HttpRequest request) {
        this(message, null, statusCode, request);
    }

    public UncheckedRestException(String message, Throwable cause, int statusCode, HttpRequest request) {
        super("[" + statusCode + "][" + request.method() + "][" + request.uri() + "] " + message, cause);
        this.statusCode = statusCode;
        this.request = request;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public HttpRequest getRequest() {
        return request;
    }
}
