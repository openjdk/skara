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

FROM oraclelinux:7.5 as prerequisites-runtime

WORKDIR /bots-build

ARG GIT_VERSION=2.19.1
ARG MERCURIAL_VERSION=4.7.2

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

RUN yum -y install make autoconf gcc curl-devel expat-devel gettext-devel openssl-devel perl-devel zlib-devel python-devel
RUN curl -sSO https://www.mercurial-scm.org/release/mercurial-${MERCURIAL_VERSION}.tar.gz && \
    echo "97f0594216f2348a2e37b2ad8a56eade044e741153fee8c584487e9934ca09fb  mercurial-4.7.2.tar.gz" | sha256sum --check - && \
    tar xvfz mercurial-${MERCURIAL_VERSION}.tar.gz && \
    cd mercurial-${MERCURIAL_VERSION} && \
    python setup.py install --force --prefix=/bots/hg
RUN curl -sSO https://mirrors.edge.kernel.org/pub/software/scm/git/git-${GIT_VERSION}.tar.xz && \
    echo "345056aa9b8084280b1b9fe1374d232dec05a34e8849028a20bfdb56e920dbb5  git-2.19.1.tar.xz" | sha256sum --check - && \
    tar xvfJ git-${GIT_VERSION}.tar.xz && \
    cd git-${GIT_VERSION} && \
    make configure && \
    ./configure --prefix=/bots/git && \
    make all && \
    make install


FROM oraclelinux:7.5

WORKDIR /bots-build

ARG JAVA_OPTIONS
ARG GRADLE_OPTIONS

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

RUN yum -y install unzip rsync

COPY gradlew ./
COPY deps.env ./
COPY Unzip.java ./

ENV JAVA_TOOL_OPTIONS=$JAVA_OPTIONS
RUN sh gradlew --no-daemon --version $GRADLE_OPTIONS

COPY --from=prerequisites-runtime /bots/git/ /bots/git/
COPY --from=prerequisites-runtime /bots/hg/ /bots/hg/
COPY ./ ./

ENV PATH=/bots/git/bin:/bots/hg/bin:${PATH}
RUN sh gradlew --no-daemon $GRADLE_OPTIONS test
