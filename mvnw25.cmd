@echo off
REM ---------------------------------------------------------------------------
REM Licensed to the Apache Software Foundation (ASF) under one
REM or more contributor license agreements.  See the NOTICE file
REM distributed with this work for additional information
REM regarding copyright ownership.  The ASF licenses this file
REM to you under the Apache License, Version 2.0 (the
REM "License"); you may not use this file except in compliance
REM with the License.  You may obtain a copy of the License at
REM
REM   http://www.apache.org/licenses/LICENSE-2.0
REM
REM Unless required by applicable law or agreed to in writing,
REM software distributed under the License is distributed on an
REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
REM KIND, either express or implied.  See the License for the
REM specific language governing permissions and limitations
REM under the License.
REM ---------------------------------------------------------------------------
REM
REM Maven launcher for the metamodel project, pinned to the current LTS:
REM Temurin JDK 25. Falls back to the mvn and JAVA_HOME on the machine when
REM the pinned toolchain is not present.
REM
REM The project's published bytecode floor stays at Java 8
REM (maven.compiler.release=8); this only controls the JDK that RUNS the
REM build. See AGENTS.md for the per-module runtime floors.
REM ---------------------------------------------------------------------------
setlocal
set "MM_ROOT=%~dp0"

set "MM_MVN=mvn"
if exist "%MM_ROOT%.tools\apache-maven-3.9.9\bin\mvn.cmd" set "MM_MVN=%MM_ROOT%.tools\apache-maven-3.9.9\bin\mvn.cmd"
if exist "%MM_ROOT%..\.tools\apache-maven-3.9.9\bin\mvn.cmd" set "MM_MVN=%MM_ROOT%..\.tools\apache-maven-3.9.9\bin\mvn.cmd"

if exist "%MM_ROOT%.tools\jdk25\jdk-25.0.4.1+1\bin\java.exe" set "JAVA_HOME=%MM_ROOT%.tools\jdk25\jdk-25.0.4.1+1"
if exist "%MM_ROOT%..\.tools\jdk25\jdk-25.0.4.1+1\bin\java.exe" set "JAVA_HOME=%MM_ROOT%..\.tools\jdk25\jdk-25.0.4.1+1"

if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MAVEN_OPTS=-Xmx1g -Dfile.encoding=UTF-8"

call "%MM_MVN%" -B %*
endlocal
