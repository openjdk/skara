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
package org.openjdk.skara.bots.hgbridge;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.json.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

class ExporterConfig {
    private List<HostedRepository> destinations;
    private URI source;
    private HostedRepository configurationRepo;
    private String configurationRef;
    private HostedRepository marksRepo;
    private String marksRef;
    private String marksAuthorName;
    private String marksAuthorEmail;
    private List<String> replacementsFile;
    private List<String> correctionsFile;
    private List<String> lowercaseFile;
    private List<String> punctuatedFile;
    private List<String> authorsFile;
    private List<String> contributorsFile;
    private List<String> sponsorsFile;

    void destinations(List<HostedRepository> destinations) {
        this.destinations = destinations;
    }

    List<HostedRepository> destinations() {
        return new ArrayList<>(destinations);
    }

    void source(URI source) {
        this.source = source;
    }

    URI source() {
        return source;
    }

    void configurationRepo(HostedRepository configurationRepo) {
        this.configurationRepo = configurationRepo;
    }

    void configurationRef(String configurationRef) {
        this.configurationRef = configurationRef;
    }

    void marksRepo(HostedRepository marksRepo) {
        this.marksRepo = marksRepo;
    }

    HostedRepository marksRepo() {
        return marksRepo;
    }

    void marksRef(String marksRef) {
        this.marksRef = marksRef;
    }

    String marksRef() {
        return marksRef;
    }

    void marksAuthorName(String marksAuthorName) {
        this.marksAuthorName = marksAuthorName;
    }

    String marksAuthorName() {
        return marksAuthorName;
    }

    void marksAuthorEmail(String marksAuthorEmail) {
        this.marksAuthorEmail = marksAuthorEmail;
    }

    String marksAuthorEmail() {
        return marksAuthorEmail;
    }

    void replacements(List<String> replacements) {
        replacementsFile = replacements;
    }

    void corrections(List<String> corrections) {
        correctionsFile = corrections;
    }

    void lowercase(List<String> lowercase) {
        lowercaseFile = lowercase;
    }

    void punctuated(List<String> punctuated) {
        punctuatedFile = punctuated;
    }

    void authors(List<String> authors) {
        authorsFile = authors;
    }

    void contributors(List<String> contributors) {
        contributorsFile = contributors;
    }

    void sponsors(List<String> sponsors) {
        sponsorsFile = sponsors;
    }

    private interface FieldParser<T> {
        T parse(JSONObject.Field value);
    }

    private <K, V> Map<K, V> parseMap(Path base, List<String> files, FieldParser<K> keyParser, FieldParser<V> valueParser) throws IOException {
        var ret = new HashMap<K, V>();
        for (var file : files) {
            var jsonData = Files.readString(base.resolve(file), StandardCharsets.UTF_8);
            var json = JSON.parse(jsonData);
            for (var field : json.fields()) {
                ret.put(keyParser.parse(field), valueParser.parse(field));
            }
        }
        return ret;
    }

    private interface ValueParser<T> {
        T parse(JSONValue value);
    }

    private <E> Set<E> parseCommits(Path base, List<String> files, ValueParser<E> valueParser) throws IOException {
        var ret = new HashSet<E>();
        for (var file : files) {
            var jsonData = Files.readString(base.resolve(file), StandardCharsets.UTF_8);
            var json = JSON.parse(jsonData);
            for (var value : json.get("commits").asArray()) {
                ret.add(valueParser.parse(value));
            }
        }
        return ret;
    }

    public Converter resolve(Path scratchPath) throws IOException {
        var localRepo = Repository.materialize(scratchPath, configurationRepo.url(), configurationRef);

        var replacements = parseMap(localRepo.root(), replacementsFile,
                                    field -> new Hash(field.name()),
                                    field -> field.value().stream()
                                                  .map(JSONValue::asString).collect(Collectors.toList()));
        var corrections = parseMap(localRepo.root(), correctionsFile,
                                   field -> new Hash(field.name()),
                                   field -> field.value().fields().stream()
                                                 .collect(Collectors.toMap(JSONObject.Field::name, sub -> sub.value().asString())));
        var lowercase = parseCommits(localRepo.root(), lowercaseFile, value -> new Hash(value.asString()));
        var punctuated = parseCommits(localRepo.root(), punctuatedFile, value -> new Hash(value.asString()));
        var authors = parseMap(localRepo.root(), authorsFile, JSONObject.Field::name, field -> field.value().asString());
        var contributors = parseMap(localRepo.root(), contributorsFile, JSONObject.Field::name, field -> field.value().asString());
        var sponsors = parseMap(localRepo.root(), sponsorsFile,
                                JSONObject.Field::name,
                                field -> field.value().stream()
                                              .map(JSONValue::asString)
                                              .collect(Collectors.toList()));

        return new HgToGitConverter(replacements, corrections, lowercase, punctuated, authors, contributors, sponsors);
    }
}
