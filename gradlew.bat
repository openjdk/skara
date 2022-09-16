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

for /f "tokens=1,2 delims==" %%A in (deps.env) do (set %%A=%%~B)
for /f %%i in ("%JDK_WINDOWS_X64_URL%") do set JDK_WINDOWS_DIR=%%~ni
for /f %%i in ("%GRADLE_URL%") do set GRADLE_DIR=%%~ni

if exist %~dp0\.jdk\%JDK_WINDOWS_DIR%.zip goto extractJdk

echo Downloading JDK...
mkdir %~dp0\.jdk
curl -L %JDK_WINDOWS_X64_URL% -o %JDK_WINDOWS_DIR%.zip
move %JDK_WINDOWS_DIR%.zip %~dp0\.jdk\
for /f "tokens=*" %%i in ('@certutil -hashfile %~dp0/.jdk/%JDK_WINDOWS_DIR%.zip sha256 ^| %WINDIR%\System32\find /v "hash of file" ^| %WINDIR%\System32\find /v "CertUtil"') do set SHA256JDK=%%i
if "%SHA256JDK%" == "%JDK_WINDOWS_X64_SHA256%" (goto extractJdk)
echo Invalid SHA256 for JDK detected (%SHA256JDK%)
goto done

:extractJdk
if exist %~dp0\.jdk\%JDK_WINDOWS_DIR% goto gradle

echo Extracting JDK...
md %~dp0\.jdk\%JDK_WINDOWS_DIR%
%WINDIR%\System32\tar -xf %~dp0/.jdk/%JDK_WINDOWS_DIR%.zip -C %~dp0/.jdk/%JDK_WINDOWS_DIR%\

:gradle
if exist %~dp0\.gradle\%GRADLE_DIR%.zip goto extractGradle

echo Downloading Gradle...
mkdir %~dp0\.gradle
curl -L %GRADLE_URL% -o %GRADLE_DIR%.zip
move %GRADLE_DIR%.zip %~dp0\.gradle\
for /f "tokens=*" %%i in ('@certutil -hashfile %~dp0/.gradle/%GRADLE_DIR%.zip sha256 ^| %WINDIR%\System32\find /v "hash of file" ^| %WINDIR%\System32\find /v "CertUtil"') do set SHA256GRADLE=%%i
if "%SHA256GRADLE%" == "%GRADLE_SHA256%" (goto extractGradle)
echo Invalid SHA256 for Gradle detected (%SHA256GRADLE%)
goto done

:extractGradle
if exist %~dp0\.gradle\%GRADLE_DIR% goto run

echo Extracting Gradle...
md %~dp0\.gradle\%GRADLE_DIR%
%WINDIR%\System32\tar -xf %~dp0/.gradle/%GRADLE_DIR%.zip -C %~dp0/.gradle/%GRADLE_DIR%

:run
for /d %%i in (%~dp0.jdk\%JDK_WINDOWS_DIR%\*) do set JAVA_HOME=%%i
for /d %%i in (%~dp0.gradle\%GRADLE_DIR%\*) do set GRADLE_HOME=%%i
%GRADLE_HOME%\bin\gradle %*

:done
