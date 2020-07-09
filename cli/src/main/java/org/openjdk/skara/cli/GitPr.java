/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.cli;

import org.openjdk.skara.args.*;
import org.openjdk.skara.cli.pr.*;
import org.openjdk.skara.proxy.HttpProxy;

import java.util.List;

public class GitPr {
    public static void main(String[] args) throws Exception {
        var commands = List.of(
                    Default.name("help")
                           .helptext("show help text")
                           .main(GitPrHelp::main),
                    Command.name("list")
                           .helptext("list open pull requests")
                           .main(GitPrList::main),
                    Command.name("fetch")
                           .helptext("fetch a pull request")
                           .main(GitPrFetch::main),
                    Command.name("show")
                           .helptext("show a pull request")
                           .main(GitPrShow::main),
                    Command.name("checkout")
                           .helptext("checkout a pull request")
                           .main(GitPrCheckout::main),
                    Command.name("apply")
                           .helptext("apply a pull request")
                           .main(GitPrApply::main),
                    Command.name("integrate")
                           .helptext("integrate a pull request")
                           .main(GitPrIntegrate::main),
                    Command.name("approve")
                           .helptext("approve a pull request")
                           .main(GitPrApprove::main),
                    Command.name("create")
                           .helptext("create a pull request")
                           .main(GitPrCreate::main),
                    Command.name("close")
                           .helptext("close a pull request")
                           .main(GitPrClose::main),
                    Command.name("set")
                           .helptext("set properties of a pull request")
                           .main(GitPrSet::main),
                    Command.name("sponsor")
                           .helptext("sponsor a pull request")
                           .main(GitPrSet::main),
                    Command.name("test")
                           .helptext("test a pull request")
                           .main(GitPrTest::main),
                    Command.name("info")
                           .helptext("show status of a pull request")
                           .main(GitPrInfo::main),
                    Command.name("issue")
                           .helptext("add, remove or create issues")
                           .main(GitPrIssue::main),
                    Command.name("reviewer")
                           .helptext("add or remove reviewers")
                           .main(GitPrReviewer::main),
                    Command.name("cc")
                           .helptext("add one or more labels")
                           .main(GitPrCC::main)
        );

        HttpProxy.setup();

        var parser = new MultiCommandParser("git pr", commands);
        var command = parser.parse(args);
        command.execute();
    }
}
