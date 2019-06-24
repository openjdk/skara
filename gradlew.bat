@echo off
rem Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
rem DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
rem
rem This code is free software; you can redistribute it and/or modify it
rem under the terms of the GNU General Public License version 2 only, as
rem published by the Free Software Foundation.
rem
rem This code is distributed in the hope that it will be useful, but WITHOUT
rem ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
rem FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
rem version 2 for more details (a copy is included in the LICENSE file that
rem accompanied this code).
rem
rem You should have received a copy of the GNU General Public License version
rem 2 along with this work; if not, write to the Free Software Foundation,
rem Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
rem
rem Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
rem or visit www.oracle.com if you need additional information or have any
rem questions.

if exist %~dp0\.jdk\openjdk-12_windows-x64_bin goto gradle

echo Downloading JDK...
mkdir %~dp0\.jdk
curl -O https://download.java.net/java/GA/jdk12/GPL/openjdk-12_windows-x64_bin.zip -o openjdk-12_windows-x64_bin.zip
move openjdk-12_windows-x64_bin.zip %~dp0\.jdk\
for /f "tokens=*" %%i in ('@certutil -hashfile %~dp0/.jdk/openjdk-12_windows-x64_bin.zip sha256 ^| find /v "hash of file" ^| find /v "CertUtil"') do set SHA256JDK=%%i
if "%SHA256JDK%" == "35a8d018f420fb05fe7c2aa9933122896ca50bd23dbd373e90d8e2f3897c4e92" (goto extractJdk)
echo Invalid SHA256 for JDK detected (%SHA256JDK%)
goto done

:extractJdk
echo Extracting JDK...
tar -xf %~dp0/.jdk/openjdk-12_windows-x64_bin.zip -C %~dp0/.jdk
ren %~dp0\.jdk\jdk-12 openjdk-12_windows-x64_bin

:gradle
if exist %~dp0\.gradle\gradle-5.2.1-bin goto run

echo Downloading Gradle...
mkdir %~dp0\.gradle
curl -OL https://services.gradle.org/distributions/gradle-5.2.1-bin.zip -o gradle-5.2.1-bin.zip
move gradle-5.2.1-bin.zip %~dp0\.gradle\
for /f "tokens=*" %%i in ('@certutil -hashfile %~dp0/.gradle/gradle-5.2.1-bin.zip sha256 ^| find /v "hash of file" ^| find /v "CertUtil"') do set SHA256GRADLE=%%i
if "%SHA256GRADLE%" == "748c33ff8d216736723be4037085b8dc342c6a0f309081acf682c9803e407357" (goto extractGradle)
echo Invalid SHA256 for Gradle detected (%SHA256GRADLE%)
goto done

:extractGradle
echo Extracting Gradle...
tar -xf %~dp0/.gradle/gradle-5.2.1-bin.zip -C %~dp0/.gradle
ren %~dp0\.gradle\gradle-5.2.1 gradle-5.2.1-bin

:run
set JAVA_HOME=%~dp0/.jdk/openjdk-12_windows-x64_bin
%~dp0\.gradle\gradle-5.2.1-bin\bin\gradle %*

:done
