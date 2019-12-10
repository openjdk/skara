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
package org.openjdk.skara.issuetracker;

import java.net.URI;

public class LinkBuilder {
    private final URI uri;
    private final String title;

    private String relationship;
    private String summary;
    private URI iconUrl;
    private String iconTitle;
    private URI statusIconUrl;
    private String statusIconTitle;
    private boolean resolved;

    LinkBuilder(URI uri, String title) {
        this.uri = uri;
        this.title = title;
    }

    public LinkBuilder relationship(String relationship) {
        this.relationship = relationship;
        return this;
    }

    public LinkBuilder summary(String summary) {
        this.summary = summary;
        return this;
    }

    public LinkBuilder iconUrl(URI iconUrl) {
        this.iconUrl = iconUrl;
        return this;
    }

    public LinkBuilder iconTitle(String iconTitle) {
        this.iconTitle = iconTitle;
        return this;
    }

    public LinkBuilder statusIconUrl(URI statusIconUrl) {
        this.statusIconUrl = statusIconUrl;
        return this;
    }

    public LinkBuilder statusIconTitle(String statusIconTitle) {
        this.statusIconTitle = statusIconTitle;
        return this;
    }

    public LinkBuilder resolved(boolean resolved) {
        this.resolved = resolved;
        return this;
    }

    public Link build() {
        return new Link(uri, title, relationship, summary, iconUrl, iconTitle, statusIconUrl, statusIconTitle, resolved);
    }
}
