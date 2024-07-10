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

package org.openjdk.skara.bots.checkout;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.*;
import org.openjdk.skara.storage.StorageBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.util.logging.Logger;

public class CheckoutBot implements Bot, WorkItem {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository from;
    private final Branch branch;
    private final Path to;
    private final Path storage;
    private final StorageBuilder<Mark> marksStorage;

    CheckoutBot(HostedRepository from, Branch branch, Path to, Path storage, StorageBuilder<Mark> marksStorage) {
        this.from = from;
        this.branch = branch;
        this.to = to;
        this.storage = storage;
        this.marksStorage = marksStorage;
    }

    private static String urlEncode(Path p) {
        return URLEncoder.encode(p.toString(), StandardCharsets.UTF_8);
    }

    private static String urlEncode(URI uri) {
        return URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8);
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CheckoutBot o)) {
            return true;
        }
        return !(o.to.equals(to) || o.from.equals(from));
    }

    @Override
    public String toString() {
        return "CheckoutBot(" + from.name() + ":" + branch.name() + ", " + to + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }

    @Override
    public Collection<WorkItem> run(Path scratch) {
        try {
            var fromDir = storage.resolve(urlEncode(from.url()));
            Repository fromRepo = null;
            if (!Files.exists(fromDir)) {
                Files.createDirectories(fromDir);
                log.info("Cloning Git repo " + from + " to " + fromDir);
                fromRepo = Repository.clone(from.authenticatedUrl(), fromDir);
            } else {
                log.info("Getting existing Git repo repository from " + fromDir);
                fromRepo = Repository.get(fromDir).orElseThrow(() ->
                    new IllegalStateException("Git repository vanished from " + fromDir));
            }
            fromRepo.fetchRemote("origin");
            fromRepo.checkout(branch);
            fromRepo.pull("origin", branch.name(), true);

            var toRepoName = to.getFileName().toString();
            var marksDir = scratch.resolve("checkout").resolve("marks").resolve(toRepoName);
            Files.createDirectories(marksDir);
            var marks = marksStorage.materialize(marksDir);
            var converter = new GitToHgConverter(branch);
            var hasConverted = false;
            try {
                if (!Files.exists(to)) {
                    log.info("Creating Hg repository at: " + to);
                    Files.createDirectories(to);
                    var toRepo = Repository.init(to, VCS.HG);
                    converter.convert(fromRepo, toRepo);
                    hasConverted = true;
                } else {
                    log.info("Found existing Hg repository at: " + to);
                    var toRepo = Repository.get(to).orElseThrow(() ->
                        new IllegalStateException("Repository vanished from " + to));
                    var existing = new ArrayList<>(marks.current());
                    Collections.sort(existing);
                    log.info("Found " + existing.size() + " existing marks");
                    converter.convert(fromRepo, toRepo, existing);
                    hasConverted = true;
                }
            } finally {
                if (hasConverted) {
                    log.info("Storing " + converter.marks().size() + " marks");
                    marks.put(converter.marks());
                } else {
                    log.info("No conversion has taken place, not updating marks");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }

    @Override
    public String name() {
        return CheckoutBotFactory.NAME;
    }

    @Override
    public String botName() {
        return name();
    }

    @Override
    public String workItemName() {
        return botName();
    }
}
