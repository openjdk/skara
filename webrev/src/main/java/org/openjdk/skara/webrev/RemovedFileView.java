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
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

class RemovedFileView implements FileView {
    private final Patch patch;
    private final Path out;
    private final List<CommitMetadata> commits;
    private final MetadataFormatter formatter;
    private final List<String> oldContent;
    private final byte[] binaryContent;
    private final WebrevStats stats;

    public RemovedFileView(ReadOnlyRepository repo, Hash base, Hash head, List<CommitMetadata> commits, MetadataFormatter formatter, Patch patch, Path out) throws IOException {
        this.patch = patch;
        this.out = out;
        this.commits = commits;
        this.formatter = formatter;
        if (patch.isTextual()) {
            binaryContent = null;
            oldContent = repo.lines(patch.source().path().get(), base).orElseThrow(IllegalArgumentException::new);
            stats = new WebrevStats(patch.asTextualPatch().stats(), oldContent.size());
        } else {
            oldContent = null;
            binaryContent = repo.show(patch.source().path().get(), base).orElseThrow(IllegalArgumentException::new);
            stats = WebrevStats.empty();
        }
    }

    @Override
    public WebrevStats stats() {
        return stats;
    }

    @Override
    public void render(Writer w) throws IOException {
        w.write("<p>\n");
        w.write("  <code>\n");
        w.write("------ ------ ------ ------ ");

        if (patch.isTextual()) {
            var oldView = new OldView(out, patch.source().path().get(), oldContent);
            oldView.render(w);

            w.write(" --- ");

            var removedPatchView = new RemovedPatchView(out, patch.source().path().get(), patch.asTextualPatch());
            removedPatchView.render(w);

            var rawView = new RawView(out, patch.source().path().get(), oldContent);
            rawView.render(w);
        } else {
            w.write(" --- --- ");
            var patchView = new RemovedPatchView(out, patch.source().path().get(), patch.asBinaryPatch());
            patchView.render(w);

            var rawView = new RawView(out, patch.source().path().get(), binaryContent);
            rawView.render(w);
        }

        w.write("  </code>\n");
        w.write("  <span class=\"file-removed\">");
        w.write(patch.source().path().get().toString());
        w.write("</span>");

        if (patch.source().type().get().isVCSLink()) {
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
