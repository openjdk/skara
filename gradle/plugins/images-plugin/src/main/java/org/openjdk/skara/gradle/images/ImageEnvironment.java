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

package org.openjdk.skara.gradle.images;

import groovy.lang.Closure;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;

import java.util.*;

public class ImageEnvironment {
    private final String name;
    private MapProperty<String, String> launchers;
    private ListProperty<String> options;
    private ListProperty<String> modules;
    private ListProperty<String> bundles;
    private Property<String> man;
    private String url;
    private String sha256;

    public ImageEnvironment(String name, ObjectFactory factory) {
        this.name = name;
        this.launchers = factory.mapProperty(String.class, String.class);
        this.options = factory.listProperty(String.class);
        this.modules = factory.listProperty(String.class);
        this.bundles = factory.listProperty(String.class);
        this.man = factory.property(String.class);
    }

    public void setLaunchers(Map<String, String> launchers) {
        this.launchers.set(launchers);
    }

    public MapProperty<String, String> getLaunchers() {
        return launchers;
    }

    public ListProperty<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options.set(options);
    }

    public void setModules(List<String> extraModules) {
        this.modules.set(extraModules);
    }

    public ListProperty<String> getModules() {
        return modules;
    }

    public void setBundles(List<String> bundles) {
        this.bundles.set(bundles);
    }

    public ListProperty<String> getBundles() {
        return bundles;
    }

    public Property<String> getMan() {
        return man;
    }

    public void setMan(String man) {
        this.man.set(man);
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public void jdk(Closure cl) {
        cl.call();
    }
}
