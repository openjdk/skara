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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

class IndexView implements View {
    private static final Template HEADER_TOP_TEMPLATE = new Template(new String[]{
        "<!DOCTYPE html>",
        "<html>",
        "  <head>",
        "    <meta charset=\"utf-8\" />",
        "    <title>${TITLE}</title>",
        "    <link rel=\"stylesheet\" href=\"style.css\" />",
        "    <link rel=\"shortcut icon\" type=\"image/x-icon\" href=\"nanoduke.ico\" />",
        "  </head>",
        "  <body>",
        "    <div class=\"summary\">",
        "      <h2 class=\"summary\">Code Review for ${TITLE}</h2>",
        "      <table class=\"summary\">"
    });

    private static final Template USER_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Prepared by:</th>",
        "          <td>${USER} on ${DATE}</td>",
        "        </tr>"
    });

    private static final Template UPSTREAM_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Compare against:</th>",
        "          <td><a href=\"${UPSTREAM}\">${UPSTREAM}</a></td>",
        "        </tr>"
    });

    private static final Template BRANCH_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Branch:</th>",
        "          <td>${BRANCH}</td>",
        "        </tr>"
    });

    private static final Template PR_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Pull request:</th>",
        "          <td><a href=\"${PR_HREF}\">${PR}</a></td>",
        "        </tr>"
    });

    private static final Template ISSUE_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Bug id:</th>",
        "          <td><a href=\"${ISSUE_HREF}\">${ISSUE}</a></td>",
        "        </tr>"
    });

    private static final Template REVISION_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Compare against version:</th>",
        "          <td>${REVISION}</td>",
        "        </tr>"
    });

    private static final Template REVISION_WITH_LINK_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Compare against version:</th>",
        "          <td><a href=\"${REVISION_HREF}\">${REVISION}</a></td>",
        "        </tr>"
    });

    private static final Template SUMMARY_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Summary of changes:</th>",
        "          <td>${STATS}</td>",
        "        </tr>"
    });

    private static final Template PATCH_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Patch of changes:</th>",
        "          <td><a href=\"${PATCH_URL}\">${PATCH}</a></td>",
        "        </tr>"
    });

    private static final Template AUTHOR_COMMENT_TEMPLATE = new Template(new String[]{
        "        <tr>",
        "          <th>Author comments:</th>",
        "          <td>",
        "            <div>",
        "${AUTHOR_COMMENT}",
        "            </div>",
        "          </td>",
        "        </tr>"
    });

    private static final Template HEADER_END_TEMPLATE = new Template(new String[]{
       "         <tr>",
       "           <th>Legend:</th>",
       "           <td><span class=\"file-modified\">Modified file</span><br><span class=\"file-removed\">Deleted file</span><br><span class=\"file-added\">New file</span></td>",
       "        </tr>",
       "      </table>",
       "    </div>"
    });

    private static final Template FOOTER_TEMPLATE = new Template(new String[]{
        "    <hr />",
        "    <p class=\"version\">",
        "      This code review page was prepared using <b>webrev</b> version ${VERSION}",
        "    </p>",
        "  </body>",
        "</html>"
    });

    private final List<FileView> files;
    private final Map<String, String> map;

    public IndexView(List<FileView> files,
                     String title,
                     String user,
                     String upstream,
                     String branch,
                     String pullRequest,
                     String issue,
                     String version,
                     Hash revision,
                     String revisionURL,
                     Path patchFile,
                     WebrevStats stats) {
        this.files = files;
        map = new HashMap<String, String>(); 

        if (user != null) {
            map.put("${USER}", user);
        }

        if (upstream != null) {
            map.put("${UPSTREAM}", upstream);
        }

        if (branch != null) {
            map.put("${BRANCH}", branch);
        }

        if (pullRequest != null) {
            map.put("${PR_HREF}", pullRequest);

            try {
                var uri = URI.create(pullRequest);
                var id = Path.of(uri.getPath()).getFileName().toString();
                map.put("${PR}", id);
            } catch (IllegalArgumentException e) {
                map.put("${PR}", pullRequest);
            }
        }


        if (version == null) {
            map.put("${VERSION}", "'unknown'");
        } else {
            map.put("${VERSION}", version);
        }

        if (issue != null) {
            map.put("${ISSUE_HREF}", issue);

            try {
                var uri = new URI(issue);
                var path = Path.of(uri.getPath());
                var name = path.getFileName().toString();
                map.put("${ISSUE}", name);
            } catch (URISyntaxException e) {
                map.put("${ISSUE_HREF}", issue);
            }
        }

        var now = ZonedDateTime.now();
        var formatter = DateTimeFormatter.ofPattern("E LLL dd HH:mm:ss z yyyy");
        map.put("${DATE}", now.format(formatter));

        map.put("${TITLE}", title);
        map.put("${REVISION}", revision.abbreviate());
        if (revisionURL != null) {
            map.put("${REVISION_HREF}", revisionURL);
        }
        map.put("${PATCH}", patchFile.toString());
        map.put("${PATCH_URL}", patchFile.toString());
        map.put("${STATS}", stats.toString());
    }

    public void render(Writer w) throws IOException {
        HEADER_TOP_TEMPLATE.render(w, map);

        if (map.containsKey("${USER}")) {
            USER_TEMPLATE.render(w, map);
        }

        if (map.containsKey("${UPSTREAM}")) {
            UPSTREAM_TEMPLATE.render(w, map);
        }

        if (map.containsKey("${REVISION_HREF}")) {
            REVISION_WITH_LINK_TEMPLATE.render(w, map);
        } else {
            REVISION_TEMPLATE.render(w, map);
        }

        if (map.containsKey("${BRANCH}")) {
            BRANCH_TEMPLATE.render(w, map);
        }

        SUMMARY_TEMPLATE.render(w, map);
        PATCH_TEMPLATE.render(w, map);

        if (map.containsKey("${AUTHOR_COMMENT}")) {
            AUTHOR_COMMENT_TEMPLATE.render(w, map);
        }

        if (map.containsKey("${PR}") && map.containsKey("${PR_HREF}")) {
            PR_TEMPLATE.render(w, map);
        }

        if (map.containsKey("${ISSUE}")) {
            ISSUE_TEMPLATE.render(w, map);
        }

        HEADER_END_TEMPLATE.render(w, map);

        for (var view : files) {
            view.render(w);
            w.write("\n");
        }

        FOOTER_TEMPLATE.render(w, map);
    }
}
