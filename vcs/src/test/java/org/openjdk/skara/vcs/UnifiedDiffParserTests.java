/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UnifiedDiffParserTests {
    @Test
    public void simple() {
        var diff1 =
            "diff --git a/bots/tester/src/test/java/org/openjdk/skara/bots/tester/inmemoryhostedrepository.java b/bots/tester/src/test/java/org/openjdk/skara/bots/tester/inmemoryhostedrepository.java\n" +
            "index 883d3f51..bff21edd 100644\n" +
            "--- a/bots/tester/src/test/java/org/openjdk/skara/bots/tester/inmemoryhostedrepository.java\n" +
            "+++ b/bots/tester/src/test/java/org/openjdk/skara/bots/tester/inmemoryhostedrepository.java\n" +
            "@@ -169,4 +169,9 @@ public void addcommitcomment(hash commit, string body) {\n" +
            "     public list<check> allchecks(hash hash) {\n" +
            "         return list.of();\n" +
            "     }\n" +
            "+\n" +
            "+    @override\n" +
            "+    public list<commitcomment> recentcommitcomments() {\n" +
            "+        return list.of();\n" +
            "+    }\n" +
            " }\n";

        var diff2 =
            "diff --git a/forge/src/main/java/org/openjdk/skara/forge/commitcomment.java b/forge/src/main/java/org/openjdk/skara/forge/commitcomment.java\n" +
            "index 5d9139c9..16eda547 100644\n" +
            "--- a/forge/src/main/java/org/openjdk/skara/forge/commitcomment.java\n" +
            "+++ b/forge/src/main/java/org/openjdk/skara/forge/commitcomment.java\n" +
            "@@ -29,9 +29,11 @@\n" +
            " import java.nio.file.path;\n" +
            " import java.time.zoneddatetime;\n" +
            " import java.util.*;\n" +
            "+import java.util.function.supplier;\n" +
            " \n" +
            " public class commitcomment extends comment {\n" +
            "-    private final hash commit;\n" +
            "+    private hash commit;\n" +
            "+    private final supplier<hash> commitsupplier;\n" +
            "     private final path path;\n" +
            "     private final int line;\n" +
            " \n" +
            "@@ -39,6 +41,16 @@ public commitcomment(hash commit, path path, int line, string id, string body, h\n" +
            "         super(id, body, author, createdat, updatedat);\n" +
            " \n" +
            "         this.commit = commit;\n" +
            "+        this.commitsupplier = null;\n" +
            "+        this.path = path;\n" +
            "+        this.line = line;\n" +
            "+    }\n" +
            "+\n" +
            "+    public commitcomment(supplier<hash> commitsupplier, path path, int line, string id, string body, hostuser author, zoneddatetime createdat, zoneddatetime updatedat) {\n" +
            "+        super(id, body, author, createdat, updatedat);\n" +
            "+\n" +
            "+        this.commit = null;\n" +
            "+        this.commitsupplier = commitsupplier;\n" +
            "         this.path = path;\n" +
            "         this.line = line;\n" +
            "     }\n" +
            "@@ -47,6 +59,9 @@ public commitcomment(hash commit, path path, int line, string id, string body, h\n" +
            "      * returns the hash of the commit.\n" +
            "      */\n" +
            "     public hash commit() {\n" +
            "+        if (commit == null) {\n" +
            "+            commit = commitsupplier.get();\n" +
            "+        }\n" +
            "         return commit;\n" +
            "     }\n" +
            " \n";

        var diff3 =
            "diff --git a/forge/src/main/java/org/openjdk/skara/forge/hostedrepository.java b/forge/src/main/java/org/openjdk/skara/forge/hostedrepository.java\n" +
            "index e9f711a1..8a612523 100644\n" +
            "--- a/forge/src/main/java/org/openjdk/skara/forge/hostedrepository.java\n" +
            "+++ b/forge/src/main/java/org/openjdk/skara/forge/hostedrepository.java\n" +
            "@@ -68,6 +68,7 @@ pullrequest createpullrequest(hostedrepository target,\n" +
            "     hash branchhash(string ref);\n" +
            "     list<hostedbranch> branches();\n" +
            "     list<commitcomment> commitcomments(hash hash);\n" +
            "+    list<commitcomment> recentcommitcomments();\n" +
            "     void addcommitcomment(hash hash, string body);\n" +
            "     optional<commitmetadata> commitmetadata(hash hash);\n" +
            "     list<check> allchecks(hash hash);\n";

        var diff4 =
            "diff --git a/forge/src/main/java/org/openjdk/skara/forge/github/githubhost.java b/forge/src/main/java/org/openjdk/skara/forge/github/githubhost.java\n" +
            "index bc11a4b3..2c29a5e9 100644\n" +
            "--- a/forge/src/main/java/org/openjdk/skara/forge/github/githubhost.java\n" +
            "+++ b/forge/src/main/java/org/openjdk/skara/forge/github/githubhost.java\n" +
            "@@ -199,10 +199,21 @@ hostuser parseuserfield(jsonvalue json) {\n" +
            "     }\n" +
            " \n" +
            "     hostuser parseuserobject(jsonvalue json) {\n" +
            "+        return hostuser(json.get(\"id\").asint(), json.get(\"login\").asstring());\n" +
            "+    }\n" +
            "+\n" +
            "+    hostuser hostuser(int id, string username) {\n" +
            "+        return hostuser.builder()\n" +
            "+                       .id(id)\n" +
            "+                       .username(username)\n" +
            "+                       .supplier(() -> user(username).orelsethrow())\n" +
            "+                       .build();\n" +
            "+    }\n" +
            "+\n" +
            "+    hostuser hostuser(string username) {\n" +
            "         return hostuser.builder()\n" +
            "-                       .id(json.get(\"id\").asint())\n" +
            "-                       .username(json.get(\"login\").asstring())\n" +
            "-                       .supplier(() -> user(json.get(\"login\").asstring()).orelsethrow())\n" +
            "+                       .username(username)\n" +
            "+                       .supplier(() -> user(username).orelsethrow())\n" +
            "                        .build();\n" +
            "     }\n" +
            " \n" +
            "@@ -269,10 +280,10 @@ jsonobject runsearch(string category, string query) {\n" +
            "             return optional.empty();\n" +
            "         }\n" +
            " \n" +
            "-        return optional.of(ashostuser(details.asobject()));\n" +
            "+        return optional.of(tohostuser(details.asobject()));\n" +
            "     }\n" +
            " \n" +
            "-    private static hostuser ashostuser(jsonobject details) {\n" +
            "+    private hostuser tohostuser(jsonobject details) {\n" +
            "         // always present\n" +
            "         var login = details.get(\"login\").asstring();\n" +
            "         var id = details.get(\"id\").asint();\n" +
            "@@ -302,7 +313,7 @@ public hostuser currentuser() {\n" +
            "                 // on windows always return \"personalaccesstoken\" as username.\n" +
            "                 // query github for the username instead.\n" +
            "                 var details = request.get(\"user\").execute().asobject();\n" +
            "-                currentuser = ashostuser(details);\n" +
            "+                currentuser = tohostuser(details);\n" +
            "             } else {\n" +
            "                 throw new illegalstateexception(\"no credentials present\");\n" +
            "             }\n";

        var diff5 =
            "diff --git a/forge/src/main/java/org/openjdk/skara/forge/github/githubrepository.java b/forge/src/main/java/org/openjdk/skara/forge/github/githubrepository.java\n" +
            "index 7198f13d..08769f62 100644\n" +
            "--- a/forge/src/main/java/org/openjdk/skara/forge/github/githubrepository.java\n" +
            "+++ b/forge/src/main/java/org/openjdk/skara/forge/github/githubrepository.java\n" +
            "@@ -268,29 +268,80 @@ public hash branchhash(string ref) {\n" +
            "                        .collect(collectors.tolist());\n" +
            "     }\n" +
            " \n" +
            "+    private commitcomment tocommitcomment(jsonvalue o) {\n" +
            "+        var hash = new hash(o.get(\"commit_id\").asstring());\n" +
            "+        var line = o.get(\"line\").isnull()? -1 : o.get(\"line\").asint();\n" +
            "+        var path = o.get(\"path\").isnull()? null : path.of(o.get(\"path\").asstring());\n" +
            "+        return new commitcomment(hash,\n" +
            "+                                 path,\n" +
            "+                                 line,\n" +
            "+                                 o.get(\"id\").tostring(),\n" +
            "+                                 o.get(\"body\").asstring(),\n" +
            "+                                 githubhost.parseuserfield(o),\n" +
            "+                                 zoneddatetime.parse(o.get(\"created_at\").asstring()),\n" +
            "+                                 zoneddatetime.parse(o.get(\"updated_at\").asstring()));\n" +
            "+    }\n" +
            "+\n" +
            "     @override\n" +
            "     public list<commitcomment> commitcomments(hash hash) {\n" +
            "         return request.get(\"commits/\" + hash.hex() + \"/comments\")\n" +
            "                       .execute()\n" +
            "                       .stream()\n" +
            "-                      .map(jsonvalue::asobject)\n" +
            "-                      .map(o -> {\n" +
            "-                           var line = o.get(\"line\").isnull()? -1 : o.get(\"line\").asint();\n" +
            "-                           var path = o.get(\"path\").isnull()? null : path.of(o.get(\"path\").asstring());\n" +
            "-                           return new commitcomment(hash,\n" +
            "-                                                    path,\n" +
            "-                                                    line,\n" +
            "-                                                    o.get(\"id\").tostring(),\n" +
            "-                                                    o.get(\"body\").asstring(),\n" +
            "-                                                    githubhost.parseuserfield(o),\n" +
            "-                                                    zoneddatetime.parse(o.get(\"created_at\").asstring()),\n" +
            "-                                                    zoneddatetime.parse(o.get(\"updated_at\").asstring()));\n" +
            "-\n" +
            "-\n" +
            "-                      })\n" +
            "+                      .map(this::tocommitcomment)\n" +
            "                       .collect(collectors.tolist());\n" +
            "     }\n" +
            " \n" +
            "+    @override\n" +
            "+    public list<commitcomment> recentcommitcomments() {\n" +
            "+        var parts = name().split(\"/\");\n" +
            "+        var owner = parts[0];\n" +
            "+        var name = parts[1];\n" +
            "+\n" +
            "+        var data = githubhost.graphql()\n" +
            "+                             .post()\n" +
            "+                             .body(json.object().put(\"query\", query))\n" +
            "+                             .execute()\n" +
            "+                             .get(\"data\");\n" +
            "+        return data.get(\"repository\")\n" +
            "+                   .get(\"commitcomments\")\n" +
            "+                   .get(\"nodes\")\n" +
            "+                   .stream()\n" +
            "+                   .map(o -> {\n" +
            "+                       var hash = new hash(o.get(\"commit\").get(\"oid\").asstring());\n" +
            "+                       var createdat = zoneddatetime.parse(o.get(\"createdat\").asstring());\n" +
            "+                       var updatedat = zoneddatetime.parse(o.get(\"updatedat\").asstring());\n" +
            "+                       var id = o.get(\"databaseid\").asstring();\n" +
            "+                       var body = o.get(\"body\").asstring();\n" +
            "+                       var user = githubhost.hostuser(o.get(\"login\").asstring());\n" +
            "+                       return new commitcomment(hash,\n" +
            "+                                                null,\n" +
            "+                                                -1,\n" +
            "+                                                id,\n" +
            "+                                                body,\n" +
            "+                                                user,\n" +
            "+                                                createdat,\n" +
            "+                                                updatedat);\n" +
            "+                   })\n" +
            "+                   .collect(collectors.tolist());\n" +
            "+    }\n" +
            "+\n" +
            "     @override\n" +
            "     public void addcommitcomment(hash hash, string body) {\n" +
            "         var query = json.object().put(\"body\", body);\n";

        var diff6 =
            "diff --git a/forge/src/main/java/org/openjdk/skara/forge/gitlab/gitlabrepository.java b/forge/src/main/java/org/openjdk/skara/forge/gitlab/gitlabrepository.java\n" +
            "index 80c78f6c..7bfb45bf 100644\n" +
            "--- a/forge/src/main/java/org/openjdk/skara/forge/gitlab/gitlabrepository.java\n" +
            "+++ b/forge/src/main/java/org/openjdk/skara/forge/gitlab/gitlabrepository.java\n" +
            "@@ -22,6 +22,7 @@\n" +
            "  */\n" +
            " package org.openjdk.skara.forge.gitlab;\n" +
            " \n" +
            "+import org.openjdk.skara.host.hostuser;\n" +
            " import org.openjdk.skara.forge.*;\n" +
            " import org.openjdk.skara.json.*;\n" +
            " import org.openjdk.skara.network.*;\n" +
            "@@ -33,6 +34,7 @@\n" +
            " import java.time.*;\n" +
            " import java.time.format.datetimeformatter;\n" +
            " import java.util.*;\n" +
            "+import java.util.function.supplier;\n" +
            " import java.util.regex.pattern;\n" +
            " import java.util.stream.collectors;\n" +
            " \n" +
            "@@ -290,27 +292,90 @@ public hash branchhash(string ref) {\n" +
            "                        .collect(collectors.tolist());\n" +
            "     }\n" +
            " \n" +
            "+    private commitcomment tocommitcomment(hash hash, jsonvalue o) {\n" +
            "+       var line = o.get(\"line\").isnull()? -1 : o.get(\"line\").asint();\n" +
            "+       var path = o.get(\"path\").isnull()? null : path.of(o.get(\"path\").asstring());\n" +
            "+       // gitlab does not offer updated_at for commit comments\n" +
            "+       var createdat = zoneddatetime.parse(o.get(\"created_at\").asstring());\n" +
            "+       // gitlab does not offer an id for commit comments\n" +
            "+       var id = \"\";\n" +
            "+       return new commitcomment(hash,\n" +
            "+                                path,\n" +
            "+                                line,\n" +
            "+                                id,\n" +
            "+                                o.get(\"note\").asstring(),\n" +
            "+                                gitlabhost.parseauthorfield(o),\n" +
            "+                                createdat,\n" +
            "+                                createdat);\n" +
            "+    }\n" +
            "+\n" +
            "     @override\n" +
            "     public list<commitcomment> commitcomments(hash hash) {\n" +
            "         return request.get(\"repository/commits/\" + hash.hex() + \"/comments\")\n" +
            "                       .execute()\n" +
            "                       .stream()\n" +
            "-                      .map(jsonvalue::asobject)\n" +
            "+                      .map(o -> tocommitcomment(hash, o))\n" +
            "+                      .collect(collectors.tolist());\n" +
            "+    }\n" +
            "+\n" +
            "+    private hash commitwithcomment(string committitle,\n" +
            "+                                   string commentbody,\n" +
            "+                                   zoneddatetime commentcreatedat,\n" +
            "+                                   hostuser author) {\n" +
            "+        var result = request.get(\"search\")\n" +
            "+                            .param(\"scope\", \"commits\")\n" +
            "+                            .param(\"search\", committitle)\n" +
            "+                            .execute()\n" +
            "+                            .stream()\n" +
            "+                            .filter(o -> o.get(\"title\").asstring().equals(committitle))\n" +
            "+                            .map(o -> new hash(o.get(\"id\").asstring()))\n" +
            "+                            .collect(collectors.tolist());\n" +
            "+        if (result.isempty()) {\n" +
            "+            throw new illegalargumentexception(\"no commit with title: \" + committitle);\n" +
            "+        }\n" +
            "+        if (result.size() > 1) {\n" +
            "+            var filtered = result.stream()\n" +
            "+                                 .flatmap(hash -> commitcomments(hash).stream()\n" +
            "+                                                                      .filter(c -> c.body().equals(commentbody))\n" +
            "+                                                                      .filter(c -> c.createdat().equals(commentcreatedat))\n" +
            "+                                                                      .filter(c -> c.author().equals(author)))\n" +
            "+                                 .map(c -> c.commit())\n" +
            "+                                 .collect(collectors.tolist());\n" +
            "+            if (filtered.isempty()) {\n" +
            "+                throw new illegalstateexception(\"no commit with title '\" + committitle +\n" +
            "+                                                \"' and comment '\" + commentbody + \"'\");\n" +
            "+            }\n" +
            "+            if (filtered.size() > 1) {\n" +
            "+                var hashes = filtered.stream().map(hash::hex).collect(collectors.tolist());\n" +
            "+                throw new illegalstateexception(\"multiple commits with identical comment '\" + commentbody + \"': \"\n" +
            "+                                                 + string.join(\",\", hashes));\n" +
            "+            }\n" +
            "+            return filtered.get(0);\n" +
            "+        }\n" +
            "+        return result.get(0);\n" +
            "+    }\n" +
            "+\n" +
            "+    @override\n" +
            "+    public list<commitcomment> recentcommitcomments() {\n" +
            "+        var twodaysago = zoneddatetime.now().minusdays(2);\n" +
            "+        var formatter = datetimeformatter.ofpattern(\"yyyy-mm-dd\");\n" +
            "+        return request.get(\"events\")\n" +
            "+                      .param(\"after\", twodaysago.format(formatter))\n" +
            "+                      .execute()\n" +
            "+                      .stream()\n" +
            "+                      .filter(o -> o.contains(\"note\") &&\n" +
            "+                                   o.get(\"note\").contains(\"noteable_type\") &&\n" +
            "+                                   o.get(\"note\").get(\"noteable_type\").asstring().equals(\"commit\"))\n" +
            "                       .map(o -> {\n" +
            "-                           var line = o.get(\"line\").isnull()? -1 : o.get(\"line\").asint();\n" +
            "-                           var path = o.get(\"path\").isnull()? null : path.of(o.get(\"path\").asstring());\n" +
            "-                           // gitlab does not offer updated_at for commit comments\n" +
            "-                           var createdat = zoneddatetime.parse(o.get(\"created_at\").asstring());\n" +
            "-                           // gitlab does not offer an id for commit comments\n" +
            "-                           var id = \"\";\n" +
            "-                           return new commitcomment(hash,\n" +
            "-                                                    path,\n" +
            "-                                                    line,\n" +
            "-                                                    id,\n" +
            "-                                                    o.get(\"note\").asstring(),\n" +
            "-                                                    gitlabhost.parseauthorfield(o),\n" +
            "-                                                    createdat,\n" +
            "-                                                    createdat);\n" +
            "+                          var createdat = zoneddatetime.parse(o.get(\"note\").get(\"created_at\").asstring());\n" +
            "+                          var body = o.get(\"note\").get(\"body\").asstring();\n" +
            "+                          var user = gitlabhost.parseauthorfield(o);\n" +
            "+                          var id = o.get(\"note\").get(\"id\").asstring();\n" +
            "+                          supplier<hash> hash = () -> commitwithcomment(o.get(\"target_title\").asstring(),\n" +
            "+                                                                        body,\n" +
            "+                                                                        at,\n" +
            "+                                                                        user);\n" +
            "+                          return new CommitComment(hash, null, -1, id, body, user, at, at);\n" +
            "                       })\n" +
            "                       .collect(Collectors.toList());\n" +
            "     }\n";

        var diff7 =
            "diff --git a/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java b/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java\n" +
            "index 489f49ef..e777f0f8 100644\n" +
            "--- a/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java\n" +
            "+++ b/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java\n" +
            "@@ -211,6 +211,14 @@ public Hash branchHash(String ref) {\n" +
            "         return commitComments.get(hash);\n" +
            "     }\n" +
            " \n" +
            "+    @Override\n" +
            "+    public List<CommitComment> recentCommitComments() {\n" +
            "+        return commitComments.values()\n" +
            "+                             .stream()\n" +
            "+                             .flatMap(e -> e.stream())\n" +
            "+                             .collect(Collectors.toList());\n" +
            "+    }\n" +
            "+\n" +
            "     @Override\n" +
            "     public void addCommitComment(Hash hash, String body) {\n" +
            "         var id = nextCommitCommentId;";

        for (var diff : List.of(diff1, diff2, diff3, diff4, diff5, diff6, diff7)) {
            var hunks = UnifiedDiffParser.parseSingleFileDiff(diff.split("\n"));
            assertFalse(hunks.isEmpty());
        }
    }

    @Test
    public void noNewline() {
        var diff =
            "diff --git a/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java b/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java\n" +
            "index 489f49ef..e777f0f8 100644\n" +
            "--- a/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java\n" +
            "+++ b/test/src/main/java/org/openjdk/skara/test/TestHostedRepository.java\n" +
            "@@ -211,6 +211,14 @@ public Hash branchHash(String ref) {\n" +
            "+    static class CustomSelectorProviderImpl extends SelectorProvider {\n" +
            "+        @Override public DatagramChannel openDatagramChannel() { return null; }\n" +
            "+        @Override public DatagramChannel openDatagramChannel(ProtocolFamily family) { return null; }\n" +
            "+        @Override public Pipe openPipe() { return null; }\n" +
            "+        @Override public AbstractSelector openSelector() { return null; }\n" +
            "+        @Override public ServerSocketChannel openServerSocketChannel() { return null; }\n" +
            "+        @Override public SocketChannel openSocketChannel() { return null; }\n" +
            "+    }\n" +
            "+}\n" +
            "\\ No newline at end of file\n";
        var hunks = UnifiedDiffParser.parseSingleFileDiff(diff.split("\n"));
        assertEquals(1, hunks.size());
        assertFalse(hunks.get(0).target().hasNewlineAtEndOfFile());
    }

    @Test
    public void binaryFile() {
        var diff =
            "diff --git a/file.bin b/file.bin\n" +
            "new file mode 100644\n" +
            "index 0000000000000000000000000000000000000000..2020dd2b626d1bcf60351a2be801548eb65c53cd\n" +
            "Binary files /dev/null and b/file.bin differ";
        var hunks = UnifiedDiffParser.parseSingleFileDiff(diff.split("\n"));
        assertEquals(List.of(), hunks);
    }
}
