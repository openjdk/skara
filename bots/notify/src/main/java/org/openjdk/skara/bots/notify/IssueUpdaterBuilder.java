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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.issuetracker.IssueProject;

import java.net.URI;
import java.util.Map;

public class IssueUpdaterBuilder {
    private IssueProject issueProject;
    private boolean reviewLink = true;
    private URI reviewIcon = null;
    private boolean commitLink = true;
    private URI commitIcon = null;
    private boolean setFixVersion = false;
    private Map<String, String> fixVersions = null;
    private boolean prOnly = false;

    public IssueUpdaterBuilder issueProject(IssueProject issueProject) {
        this.issueProject = issueProject;
        return this;
    }

    public IssueUpdaterBuilder reviewLink(boolean reviewLink) {
        this.reviewLink = reviewLink;
        return this;
    }

    public IssueUpdaterBuilder reviewIcon(URI reviewIcon) {
        this.reviewIcon = reviewIcon;
        return this;
    }

    public IssueUpdaterBuilder commitLink(boolean commitLink) {
        this.commitLink = commitLink;
        return this;
    }

    public IssueUpdaterBuilder commitIcon(URI commitIcon) {
        this.commitIcon = commitIcon;
        return this;
    }

    public IssueUpdaterBuilder setFixVersion(boolean setFixVersion) {
        if (setFixVersion && prOnly) {
            throw new IllegalArgumentException("Cannot combine setFixVersion with prOnly");
        }
        this.setFixVersion = setFixVersion;
        return this;
    }

    public IssueUpdaterBuilder fixVersions(Map<String, String> fixVersions) {
        this.fixVersions = fixVersions;
        return this;
    }

    public IssueUpdaterBuilder prOnly(boolean prOnly) {
        if (prOnly && setFixVersion) {
            throw new IllegalArgumentException("Cannot combine prOnly with setFixVersion");
        }
        this.prOnly = prOnly;
        return this;
    }

    public IssueUpdater build() {
        return new IssueUpdater(issueProject, reviewLink, reviewIcon, commitLink, commitIcon, setFixVersion, fixVersions, prOnly);
    }
}