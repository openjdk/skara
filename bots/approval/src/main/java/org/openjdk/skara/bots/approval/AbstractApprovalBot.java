/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.approval;

import java.util.List;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.IssueProject;

public abstract class AbstractApprovalBot implements Bot {
    private final List<ApprovalInfo> approvalInfos;
    private final IssueProject issueProject;

    AbstractApprovalBot(List<ApprovalInfo> approvalInfos, IssueProject issueProject) {
        this.approvalInfos = approvalInfos;
        this.issueProject = issueProject;
    }

    List<ApprovalInfo> approvalInfos() {
        return approvalInfos;
    }

    IssueProject issueProject() {
        return issueProject;
    }

    boolean isUpdateChange(PullRequest pr) {
        return approvalInfos != null &&
                approvalInfos.stream().anyMatch(info -> approvalInfoMatch(info, pr));
    }

    boolean approvalInfoMatch(ApprovalInfo info, PullRequest pr) {
        return info.repo().isSame(pr.repository()) &&
                info.branchPattern().matcher(pr.targetRef()).matches();
    }
}
