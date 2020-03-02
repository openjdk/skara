# Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.

BUILD=build
prefix=$(HOME)/.local
bindir=$(prefix)/bin
sharedir=$(prefix)/share
mandir=$(prefix)/man

LAUNCHERS=$(addprefix $(bindir)/,$(notdir $(wildcard $(BUILD)/bin/git-*)))
MANPAGES=$(addprefix $(mandir)/man1/,$(notdir $(wildcard $(BUILD)/bin/man/man1/*)))

all:
	@sh gradlew

check:
	@sh gradlew test

test:
	@sh gradlew test

clean:
	@sh gradlew clean

images:
	@sh gradlew images

bots:
	@sh gradlew :bots:cli:images

offline:
	@sh gradlew :offline

reproduce:
	@sh gradlew :reproduce

install: all $(LAUNCHERS) $(MANPAGES) $(sharedir)/skara
	@echo "Successfully installed to $(prefix)"

uninstall:
	@rm -rf $(sharedir)/skara
	@rm $(LAUNCHERS)
	@rm $(MANPAGES)

$(mandir)/man1/%: $(BUILD)/bin/man/man1/%
	@mkdir -p $(mandir)/man1
	@cp $< $@

$(sharedir)/skara: $(BUILD)/image
	@mkdir -p $(sharedir)
	@rm -rf $@
	@cp -r $< $@

$(bindir)/%: $(BUILD)/bin/%
	@mkdir -p $(bindir)
	@sed 's~export JAVA_HOME=.*$$~export JAVA_HOME\=$(sharedir)\/skara~' < $< > $@
	@chmod 755 $@

.PHONY: all bots check clean images install test uninstall
