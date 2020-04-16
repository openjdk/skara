#!/bin/sh

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

set -e

die() {
    echo "$1" 1>&2
    exit 1
}

exists() {
    command -v "$1" >/dev/null 2>&1
}

download() {
    URL="$1"
    FILENAME="$(basename $2)"
    DIRECTORY="$(dirname $2)"
    if exists curl; then
        curl -L "${URL}" -o "${FILENAME}"
        mv "${FILENAME}" "${DIRECTORY}/${FILENAME}"
    elif exists wget; then
        wget -O "${DIRECTORY}/${FILENAME}" "${URL}"
    else
        die "error: neither 'wget' nor 'curl' available, can't download file"
    fi
}

checksum() {
    FILENAME="$1"
    SHA256="$2"
    if exists shasum; then
        echo "${SHA256}  ${FILENAME}" | shasum -a 256 -c >/dev/null -
        if [ "$?" != "0" ]; then
            die "error: did not get expected SHA256 hash for ${FILENAME}"
        fi
    elif exists sha256sum; then
        echo "${SHA256}  ${FILENAME}" | sha256sum -c >/dev/null -
        if [ "$?" != "0" ]; then
            die "error: did not get expected SHA256 hash for ${FILENAME}"
        fi
    else
        die "error: neither 'shasum' nor 'sha256sum' available, can't checksum file"
    fi
}

extract_tar() {
    FILENAME="$1"
    DIRECTORY="$2"
    mkdir -p "${DIRECTORY}"

    tar -xf "${FILENAME}" -C "${DIRECTORY}"
}

extract_zip() {
    FILENAME="$1"
    DIRECTORY="$2"

    mkdir -p "${DIRECTORY}"
    unzip "${FILENAME}" -d "${DIRECTORY}" > /dev/null
}

DIR=$(dirname $0)
ARCH=$(uname -m)
OS=$(uname)

. $(dirname "${0}")/deps.env
if [ "${ARCH}" = "x86_64" ]; then
    case "${OS}" in
        Linux )
            JDK_URL="${JDK_LINUX_X64_URL}"
            JDK_SHA256="${JDK_LINUX_X64_SHA256}"
            ;;
        Darwin )
            JDK_URL="${JDK_MACOS_X64_URL}"
            JDK_SHA256="${JDK_MACOS_X64_SHA256}"
            ;;
        CYGWIN_NT* )
            JDK_URL="${JDK_WINDOWS_X64_URL}"
            JDK_SHA256="${JDK_WINDOWS_X64_SHA256}"
            ;;
    esac
fi

if [ -z "${HTTPS_PROXY}" -a -z "${https_proxy}" -a -z "${HTTP_PROXY}" -a -z "${http_proxy}" ]; then
    # No HTTP(S) proxy configured via environment, check if configured via Git
    if exists git; then
        GIT_HTTP_PROXY="$(git config http.proxy)"
        if [ ! -z "${GIT_HTTP_PROXY}" ]; then
            export HTTPS_PROXY="${GIT_HTTP_PROXY}"
            export https_proxy="${GIT_HTTP_PROXY}"
            export HTTP_PROXY="${GIT_HTTP_PROXY}"
            export http_proxy="${GIT_HTTP_PROXY}"
        fi
    fi
fi

if [ ! -z "${JDK_URL}" ]; then
    JDK_FILENAME="${DIR}/.jdk/$(basename ${JDK_URL})"
    if [ "${OS}" = "Linux" -o "${OS}" = "Darwin" ]; then
        JDK_DIR="${DIR}/.jdk/$(basename -s '.tar.gz' ${JDK_URL})"
    else
        JDK_DIR="${DIR}/.jdk/$(basename -s '.zip' ${JDK_URL})"
    fi

    if [ ! -d "${JDK_DIR}" ]; then
        mkdir -p ${DIR}/.jdk
        if [ ! -f "${JDK_FILENAME}" ]; then
            if [ -f "${JDK_URL}" ]; then
                echo "Copying JDK..."
                cp "${JDK_URL}" "${JDK_FILENAME}"
            else
                echo "Downloading JDK..."
                download ${JDK_URL} "${JDK_FILENAME}"
                checksum "${JDK_FILENAME}" ${JDK_SHA256}
            fi
        fi
        echo "Extracting JDK..."
        if [ "${OS}" = "Linux" -o "${OS}" = "Darwin" ]; then
            extract_tar "${JDK_FILENAME}" "${JDK_DIR}"
        else
            extract_zip "${JDK_FILENAME}" "${JDK_DIR}"
        fi
    fi

    if [ "${OS}" = "Darwin" ]; then
        EXECUTABLE_FILTER='-perm +111'
        LAUNCHER='java'
    elif [ "${OS}" = "Linux" ]; then
        EXECUTABLE_FILTER='-executable'
        LAUNCHER='java'
    else
        LAUNCHER='java.exe'
    fi

    JAVA_LAUNCHER=$(find "${JDK_DIR}" -type f ${EXECUTABLE_FILTER} | grep ".*/bin/${LAUNCHER}$")
    export JAVA_HOME="$(dirname $(dirname ${JAVA_LAUNCHER}))"
else
    JAVA_LAUNCHER="java"
fi

GRADLE_FILENAME="${DIR}/.gradle/$(basename ${GRADLE_URL})"
GRADLE_DIR="${DIR}/.gradle/$(basename -s '.zip' ${GRADLE_URL})"

if [ ! -d "${GRADLE_DIR}" ]; then
    mkdir -p "${DIR}/.gradle"
    if [ ! -f "${GRADLE_FILENAME}" ]; then
        echo "Downloading Gradle..."
        download ${GRADLE_URL} "${GRADLE_FILENAME}"
    fi
    checksum ${GRADLE_FILENAME} ${GRADLE_SHA256}
    echo "Extracting Gradle..."
    if [ "${OS}" = "Linux" -o "${OS}" = "Darwin" ]; then
        if exists unzip; then
            extract_zip "${GRADLE_FILENAME}" "${GRADLE_DIR}"
        else
            "${JAVA_LAUNCHER}" "${DIR}"/Unzip.java "${GRADLE_FILENAME}" "${GRADLE_DIR}"
        fi
    else
        extract_zip "${GRADLE_FILENAME}" "${GRADLE_DIR}"
    fi
fi

GRADLE_LAUNCHER=$(find "${GRADLE_DIR}" | grep '.*/bin/gradle$')
chmod u+x "${GRADLE_LAUNCHER}"

if [ "${OS}" = "Linux" ]; then
    export LC_ALL=en_US.UTF-8
    export LANG=en_US.UTF-8
    export LANGUAGE=en_US.UTF-8
fi

exec "${GRADLE_LAUNCHER}" "$@"
