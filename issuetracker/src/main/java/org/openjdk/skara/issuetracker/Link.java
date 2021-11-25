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
import java.util.*;

public class Link {
    private final URI uri;
    private final String title;
    private final String relationship;
    private final String summary;
    private final URI iconUrl;
    private final String iconTitle;
    private final URI statusIconUrl;
    private final String statusIconTitle;
    private final boolean resolved;
    private final Issue linked;

    Link(URI uri, String title, String relationship, String summary, URI iconUrl, String iconTitle, URI statusIconUrl, String statusIconTitle, boolean resolved, Issue linked) {
        this.uri = uri;
        this.title = title;
        this.relationship = relationship;
        this.summary = summary;
        this.iconUrl = iconUrl;
        this.iconTitle = iconTitle;
        this.statusIconUrl = statusIconUrl;
        this.statusIconTitle = statusIconTitle;
        this.resolved = resolved;
        this.linked = linked;
    }

    public static WebLinkBuilder create(URI uri, String title) {
        return new WebLinkBuilder(uri, title);
    }

    public static IssueLinkBuilder create(Issue issue, String relationship) {
        return new IssueLinkBuilder(issue, relationship);
    }

    public Optional<URI> uri() {
        return Optional.ofNullable(uri);
    }

    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    public Optional<Issue> issue() {
        return Optional.ofNullable(linked);
    }

    public Optional<String> relationship() {
        return Optional.ofNullable(relationship);
    }

    public Optional<String> summary() {
        return Optional.ofNullable(summary);
    }

    public Optional<URI> iconUrl() {
        return Optional.ofNullable(iconUrl);
    }

    public Optional<String> iconTitle() {
        return Optional.ofNullable(iconTitle);
    }

    public Optional<URI> statusIconUrl() {
        return Optional.ofNullable(statusIconUrl);
    }

    public Optional<String> statusIconTitle() {
        return Optional.ofNullable(statusIconTitle);
    }

    public boolean resolved() {
        return resolved;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Link link = (Link) o;
        return resolved == link.resolved &&
                Objects.equals(uri, link.uri) &&
                Objects.equals(title, link.title) &&
                Objects.equals(relationship, link.relationship) &&
                Objects.equals(linked, link.linked) &&
                Objects.equals(summary, link.summary) &&
                Objects.equals(iconUrl, link.iconUrl) &&
                Objects.equals(iconTitle, link.iconTitle) &&
                Objects.equals(statusIconUrl, link.statusIconUrl) &&
                Objects.equals(statusIconTitle, link.statusIconTitle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, title, relationship, summary, iconUrl, iconTitle, statusIconUrl, statusIconTitle, resolved);
    }

    @Override
    public String toString() {
        return "Link{" +
                "uri=" + uri +
                ", title='" + title + '\'' +
                ", relationship='" + relationship + '\'' +
                ", linked='" + linked + '\'' +
                ", summary='" + summary + '\'' +
                ", iconUrl=" + iconUrl +
                ", iconTitle='" + iconTitle + '\'' +
                ", statusIconUrl=" + statusIconUrl +
                ", statusIconTitle='" + statusIconTitle + '\'' +
                ", resolved=" + resolved +
                '}';
    }
}
