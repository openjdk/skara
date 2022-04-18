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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.PullRequest;

import java.util.List;

public record LabelsToChange(List<String> labelsToAdd, List<String> labelsToRemove) {
    /**
     * Change the labels in the pull request.
     * @param pr the pull request
     */
    public void changeLabels(PullRequest pr) {
        if (labelsToAdd != null && !labelsToAdd.isEmpty()) {
            for (var label : labelsToAdd) {
                if (!pr.labelNames().contains(label)) {
                    pr.addLabel(label);
                }
            }
        }
        if (labelsToRemove != null && !labelsToRemove.isEmpty()) {
            for (var label : labelsToRemove) {
                if (pr.labelNames().contains(label)) {
                    pr.removeLabel(label);
                }
            }
        }
    }
}
