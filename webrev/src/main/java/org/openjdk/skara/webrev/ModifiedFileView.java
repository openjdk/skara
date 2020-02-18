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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

class ModifiedFileView implements FileView {
    private final Patch patch;
    private final Path out;
    private final Navigation navigation;
    private final List<CommitMetadata> commits;
    private final MetadataFormatter formatter;
    private final List<String> oldContent;
    private final List<String> newContent;
    private final byte[] binaryContent;
    private final WebrevStats stats;

    public ModifiedFileView(ReadOnlyRepository repo, Hash base, Hash head, List<CommitMetadata> commits, MetadataFormatter formatter, Patch patch, Path out, Navigation navigation) throws IOException {
        this.patch = patch;
        this.out = out;
        this.navigation = navigation;
        this.commits = commits;
        this.formatter = formatter;
        if (patch.isTextual()) {
            binaryContent = null;
            oldContent = repo.lines(patch.source().path().get(), base).orElseThrow(() -> {
                throw new IllegalArgumentException("Could not get content for file " +
                                                   patch.source().path().get() +
                                                   " at revision " + base);
            });
            if (head == null) {
                var path = repo.root().resolve(patch.target().path().get());
                if (patch.target().type().get().isVCSLink()) {
                    var tip = repo.head();
                    var content = repo.lines(patch.target().path().get(), tip).orElseThrow(() -> {
                        throw new IllegalArgumentException("Could not get content for file " +
                                                           patch.target().path().get() +
                                                           " at revision " + tip);
                    });
                    newContent = List.of(content.get(0) + "-dirty");
                } else {
                    newContent = Files.readAllLines(path);
                }
            } else {
                newContent = repo.lines(patch.target().path().get(), head).orElseThrow(() -> {
                    throw new IllegalArgumentException("Could not get content for file " +
                                                       patch.target().path().get() +
                                                       " at revision " + head);
                });
            }
            stats = new WebrevStats(patch.asTextualPatch().stats(), newContent.size());
        } else {
            oldContent = null;
            newContent = null;
            if (head == null) {
                binaryContent = Files.readAllBytes(repo.root().resolve(patch.target().path().get()));
            } else {
                binaryContent = repo.show(patch.target().path().get(), head).orElseThrow(() -> {
                    throw new IllegalArgumentException("Could not get content for file " +
                                                       patch.target().path().get() +
                                                       " at revision " + head);
                });
            }
            stats = WebrevStats.empty();
        }
    }

    @Override
    public WebrevStats stats() {
        return stats;
    }

    private void renderTextual(Writer w) throws IOException {
        var targetPath = patch.target().path().get();
        var cdiffView = new CDiffView(out, targetPath, patch.asTextualPatch(), navigation, oldContent, newContent);
        cdiffView.render(w);

        var udiffView = new UDiffView(out, targetPath, patch.asTextualPatch(), navigation, oldContent, newContent);
        udiffView.render(w);

        var sdiffView = new SDiffView(out, targetPath, patch.asTextualPatch(), navigation, oldContent, newContent);
        sdiffView.render(w);

        var framesView = new FramesView(out, targetPath, patch.asTextualPatch(), navigation, oldContent, newContent);
        framesView.render(w);

        var oldView = new OldView(out, targetPath, oldContent);
        oldView.render(w);

        var newView = new NewView(out, patch.source().path().get(), newContent);
        newView.render(w);

        var patchView = new PatchView(out, targetPath, patch.asTextualPatch(), oldContent, newContent);
        patchView.render(w);

        var rawView = new RawView(out, targetPath, newContent);
        rawView.render(w);

        w.write("  </code>\n");
    }

    private void renderBinary(Writer w) throws IOException {
        w.write("------ ------ ------ ------ --- --- ");

        var patchView = new PatchView(out, patch.target().path().get(), patch.asBinaryPatch());
        patchView.render(w);

        var rawView = new RawView(out, patch.target().path().get(), binaryContent);
        rawView.render(w);

        w.write("  </code>\n");
    }

    @Override
    public void render(Writer w) throws IOException {
        w.write("<p>\n");
        w.write("  <code>\n");

        if (patch.isBinary()) {
            renderBinary(w);
        } else {
            renderTextual(w);
        }

        w.write("  <span class=\"file-modified\">");
        w.write(patch.target().path().get().toString());
        w.write("</span>");

        if (patch.status().isRenamed()) {
            w.write(" <i>(was ");
            w.write(patch.source().path().get().toString());
            w.write(")</i>");
        } else if (patch.status().isCopied()) {
            w.write(" <i>(copied from ");
            w.write(patch.source().path().get().toString());
            w.write(")</i>");
        }

        if (patch.target().type().get().isVCSLink()) {
            w.write(" <i>(submodule)</i>\n");
        } else if (patch.isBinary()) {
            w.write(" <i>(binary)</i>\n");
        } else {
            w.write("\n");
        }

        w.write("<p>\n");

        if (patch.isTextual()) {
            w.write("<blockquote>\n");
            w.write("  <pre>\n");
            w.write(commits.stream()
                           .map(formatter::format)
                           .collect(Collectors.joining("\n")));
            w.write("  </pre>\n");
            w.write("  <span class=\"stat\">\n");
            w.write(stats.toString());
            w.write("  </span>");
            w.write("</blockquote>\n");
        }
    }
}
