@REM
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM     http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM

@echo off

pushd %~dp0..\..
if NOT DEFINED IOTDB_AINODE_HOME set IOTDB_AINODE_HOME=%cd%

:initial
if "%1"=="" goto done
set aux=%1
if "%aux:~0,1%"=="-" (
   set nome=%aux:~1,250%
) else (
   set "%nome%=%1"
   set nome=
)
shift
goto initial

:done
for /f  "eol=# tokens=2 delims==" %%i in ('findstr /i "^ain_rpc_port"
%IOTDB_AINODE_HOME%\conf\iotdb-ainode.properties') do (
  set ain_rpc_port=%%i
)

echo Check whether the rpc_port is used..., port is %ain_rpc_port%

for /f  "eol=# tokens=2 delims==" %%i in ('findstr /i "ain_rpc_address"
%IOTDB_AINODE_HOME%\conf\iotdb-ainode.properties') do (
  set ain_rpc_address=%%i
)

if defined t (
    for /f "tokens=2 delims=/" %%a in ("%t%") do set "ain_rpc=%%a"
) else (
    set ain_rpc=%ain_rpc_address%:%ain_rpc_port%
)

echo Target AINode to be stopped: %ain_rpc%

for /f "tokens=5" %%a in ('netstat /ano ^| findstr /r /c:"^ *TCP *%ain_rpc%.*$"') do (
  taskkill /f /pid %%a
  echo Close AINode, PID: %%a
)
