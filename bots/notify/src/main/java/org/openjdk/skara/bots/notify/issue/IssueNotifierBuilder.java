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
package org.openjdk.skara.bots.notify.issue;

import java.util.regex.Pattern;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.IssueProject;

import java.net.URI;
import java.util.*;

class IssueNotifierBuilder {
    private IssueProject issueProject;
    private boolean reviewLink = true;
    private URI reviewIcon = null;
    private boolean commitLink = true;
    private URI commitIcon = null;
    private boolean setFixVersion = false;
    private LinkedHashMap<Pattern, String> fixVersions = null;
    private LinkedHashMap<Pattern, List<Pattern>> altFixVersions = null;
    private boolean prOnly = true;
    private boolean repoOnly = false;
    private String buildName = null;
    private HostedRepository censusRepository = null;
    private String censusRef = null;
    private String namespace = "openjdk.org";
    private boolean useHeadVersion = false;
    private HostedRepository originalRepository;
    private boolean resolve = true;
    private Set<String> tagIgnoreOpt = Set.of();
    private boolean tagMatchPrefix = false;
    private List<IssueNotifier.BranchSecurity> defaultSecurity = List.of();
    private boolean avoidForwardports = false;
    private boolean multiFixVersions = false;

    IssueNotifierBuilder issueProject(IssueProject issueProject) {
        this.issueProject = issueProject;
        return this;
    }

    IssueNotifierBuilder reviewLink(boolean reviewLink) {
        this.reviewLink = reviewLink;
        return this;
    }

    IssueNotifierBuilder reviewIcon(URI reviewIcon) {
        this.reviewIcon = reviewIcon;
        return this;
    }

    IssueNotifierBuilder commitLink(boolean commitLink) {
        this.commitLink = commitLink;
        return this;
    }

    IssueNotifierBuilder commitIcon(URI commitIcon) {
        this.commitIcon = commitIcon;
        return this;
    }

    public IssueNotifierBuilder setFixVersion(boolean setFixVersion) {
        prOnly = false;
        this.setFixVersion = setFixVersion;
        return this;
    }

    public IssueNotifierBuilder fixVersions(LinkedHashMap<Pattern, String> fixVersions) {
        this.fixVersions = fixVersions;
        return this;
    }

    public IssueNotifierBuilder altFixVersions(LinkedHashMap<Pattern, List<Pattern>> altFixVersions) {
        this.altFixVersions = altFixVersions;
        return this;
    }

    public IssueNotifierBuilder prOnly(boolean prOnly) {
        this.prOnly = prOnly;
        return this;
    }

    public IssueNotifierBuilder repoOnly(boolean repoOnly) {
        this.repoOnly = repoOnly;
        return this;
    }

    public IssueNotifierBuilder buildName(String buildName) {
        this.buildName = buildName;
        return this;
    }

    public IssueNotifierBuilder censusRepository(HostedRepository censusRepository) {
        this.censusRepository = censusRepository;
        return this;
    }

    public IssueNotifierBuilder censusRef(String censusRef) {
        this.censusRef = censusRef;
        return this;
    }

    public IssueNotifierBuilder namespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    public IssueNotifierBuilder useHeadVersion(boolean useHeadVersion) {
        this.useHeadVersion = useHeadVersion;
        return this;
    }

    public IssueNotifierBuilder originalRepository(HostedRepository originalRepository) {
        this.originalRepository = originalRepository;
        return this;
    }

    public IssueNotifierBuilder resolve(boolean resolve) {
        this.resolve = resolve;
        return this;
    }

    public IssueNotifierBuilder tagIgnoreOpt(Set<String> tagIgnoreOpt) {
        this.tagIgnoreOpt = tagIgnoreOpt;
        return this;
    }

    public IssueNotifierBuilder tagMatchPrefix(boolean tagMatchPrefix) {
        this.tagMatchPrefix = tagMatchPrefix;
        return this;
    }

    public IssueNotifierBuilder defaultSecurity(List<IssueNotifier.BranchSecurity> defaultSecurity) {
        this.defaultSecurity = defaultSecurity;
        return this;
    }

    public IssueNotifierBuilder avoidForwardports(boolean avoidForwardports) {
        this.avoidForwardports = avoidForwardports;
        return this;
    }

    public IssueNotifierBuilder multiFixVersions(boolean multiFixVersions) {
        this.multiFixVersions = multiFixVersions;
        return this;
    }

    public boolean prOnly() {
        return prOnly;
    }

    public boolean resolve() {
        return resolve;
    }

    IssueNotifier build() {
        return new IssueNotifier(issueProject, reviewLink, reviewIcon, commitLink, commitIcon,
                setFixVersion, fixVersions, altFixVersions, prOnly,
                repoOnly, buildName, censusRepository, censusRef, namespace, useHeadVersion, originalRepository,
                resolve, tagIgnoreOpt, tagMatchPrefix, defaultSecurity, avoidForwardports, multiFixVersions);
    }
}
