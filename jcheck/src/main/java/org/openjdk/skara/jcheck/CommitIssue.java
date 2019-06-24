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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

public abstract class CommitIssue extends Issue {
    private final Commit commit;
    private final CommitMessage message;

    static final class Metadata {
        private final Severity severity;
        private final Check check;
        private final Commit commit;
        private final CommitMessage message;

        private Metadata(Severity severity,
                         Check check,
                         Commit commit,
                         CommitMessage message) {
            this.severity = severity;
            this.check = check;
            this.commit = commit;
            this.message = message;
        }
    }

    CommitIssue(CommitIssue.Metadata metadata) {
        super(metadata.severity, metadata.check);
        this.commit = metadata.commit;
        this.message = metadata.message;
    }

    public Commit commit() {
        return commit;
    }

    public CommitMessage message() {
        return message;
    }

    static Metadata metadata(Commit commit, CommitMessage message, JCheckConfiguration conf, CommitCheck check) {
        return new Metadata(conf.checks().severity(check.name()), check, commit, message);
    }
}
