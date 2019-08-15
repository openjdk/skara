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
package org.openjdk.skara.bots.hgbridge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.host.network.URIBuilder;
import org.openjdk.skara.json.*;

import java.util.*;
import java.util.stream.Collectors;

public class JBridgeBotFactory implements BotFactory {
    private List<String> getSpecific(String field, JSONObject base, JSONObject specific) {
        var ret = new ArrayList<String>();
        if (base.contains(field)) {
            ret.add(base.get(field).asString());
        }
        if (specific.contains(field)) {
            ret.add(specific.get(field).asString());
        }
        return ret;
    }

    @Override
    public String name() {
        return "hgbridge";
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();
        var storage = configuration.storageFolder();

        var marks = specific.get("marks").asObject();
        var marksRepo = configuration.repository(marks.get("repository").asString());
        var marksRef = marks.get("ref").asString();
        var marksName = marks.get("name").asString();
        var marksEmail = marks.get("email").asString();

        var converters = specific.get("converters").stream()
                                 .map(JSONValue::asObject)
                                 .flatMap(base -> base.get("repositories").stream()
                                                      .map(JSONValue::asObject)
                                                      .map(repo -> {
                                                          var converterConfig = new ExporterConfig();
                                                          // Base configuration options
                                                          converterConfig.configurationRepo(configuration.repository(base.get("repository").asString()));
                                                          converterConfig.configurationRef(base.get("ref").asString());

                                                          // Mark storage configuration
                                                          converterConfig.marksRepo(marksRepo);
                                                          converterConfig.marksRef(marksRef);
                                                          converterConfig.marksAuthorName(marksName);
                                                          converterConfig.marksAuthorEmail(marksEmail);

                                                          // Repository specific overrides
                                                          converterConfig.replacements(getSpecific("replacements", base, repo));
                                                          converterConfig.corrections(getSpecific("corrections", base, repo));
                                                          converterConfig.lowercase(getSpecific("lowercase", base, repo));
                                                          converterConfig.punctuated(getSpecific("punctuated", base, repo));
                                                          converterConfig.authors(getSpecific("authors", base, repo));
                                                          converterConfig.contributors(getSpecific("contributors", base, repo));
                                                          converterConfig.sponsors(getSpecific("sponsors", base, repo));

                                                          // Repository specific only
                                                          converterConfig.source(URIBuilder.base(repo.get("source").asString()).build());
                                                          converterConfig.destinations(repo.get("destinations").stream()
                                                                                           .map(JSONValue::asString)
                                                                                           .map(configuration::repository)
                                                                                           .collect(Collectors.toList()));
                                                          return converterConfig;
                                                      })
                                 )
                                 .collect(Collectors.toList());

        return converters.stream()
                         .map(config -> new JBridgeBot(config, storage))
                         .collect(Collectors.toList());
    }
}
