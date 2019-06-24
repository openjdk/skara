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
    STRIP="$3"
    mkdir -p "${DIRECTORY}"

    tar -xf "${FILENAME}" --strip-components=${STRIP} -C "${DIRECTORY}"
}

extract_zip() {
    FILENAME="$1"
    DIRECTORY="$2"

    mkdir -p "${DIRECTORY}"
    unzip "${FILENAME}" -d "${DIRECTORY}"
}

DIR=$(dirname $0)
OS=$(uname)

if [ "$1" = "--jdk" ]; then
    JDK_URL="$2"
    JDK_SHA256=''
    shift
    shift
else
    if [ "${OS}" = "Linux" ]; then
        JDK_URL='https://download.java.net/java/GA/jdk12/GPL/openjdk-12_linux-x64_bin.tar.gz'
        JDK_SHA256='b43bc15f4934f6d321170419f2c24451486bc848a2179af5e49d10721438dd56'
    elif [ "${OS}" = "Darwin" ]; then
        JDK_URL='https://download.java.net/java/GA/jdk12/GPL/openjdk-12_osx-x64_bin.tar.gz'
        JDK_SHA256='52164a04db4d3fdfe128cfc7b868bc4dae52d969f03d53ae9d4239fe783e1a3a'
    else
        die "error: unknown operating system: ${OS}"
    fi
fi

if [ "${OS}" = "Linux" ]; then
    STRIP=1
elif [ "${OS}" = "Darwin" ]; then
    STRIP=2
fi

JDK_FILENAME="${DIR}/.jdk/$(basename ${JDK_URL})"
JDK_DIR="${DIR}/.jdk/$(basename -s '.tar.gz' ${JDK_URL})"

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
    extract_tar "${JDK_FILENAME}" "${JDK_DIR}" ${STRIP}
fi

GRADLE_URL="https://services.gradle.org/distributions/gradle-5.2.1-bin.zip"
GRADLE_SHA256="748c33ff8d216736723be4037085b8dc342c6a0f309081acf682c9803e407357"
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
    extract_zip "${GRADLE_FILENAME}" "${GRADLE_DIR}"
fi

if [ "${OS}" = "Darwin" ]; then
    export JAVA_HOME="${JDK_DIR}/Contents/Home"
elif [ "${OS}" = "Linux" ]; then
    export JAVA_HOME="${JDK_DIR}"
fi

exec "${GRADLE_DIR}/gradle-5.2.1/bin/gradle" "$@"
