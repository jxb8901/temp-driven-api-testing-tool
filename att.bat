@echo off
rem Author: Jeffrey + ChatGPT
setlocal EnableExtensions DisableDelayedExpansion
set "ROOT_DIR=%~dp0"
if not defined ATT_HOME set "ATT_HOME=%ROOT_DIR%"
for %%I in ("%ATT_HOME%\.") do set "ATT_HOME=%%~fI"
cd /d "%ROOT_DIR%" || exit /b 2
where java >nul 2>&1
if errorlevel 1 (echo A Java 8 or later runtime is required. 1>&2& exit /b 2)
set "APP_JAR="
for %%F in ("%ATT_HOME%\lib\att-local-*.jar") do call :select_app_jar "%%~fF"
if errorlevel 1 exit /b 2
if defined APP_JAR goto packaged
set "MISSING_CLASSES="
if not exist "%ROOT_DIR%att-cli\target\classes\att\FrameworkRunner.class" set "MISSING_CLASSES=%MISSING_CLASSES% att-cli/target/classes/att/FrameworkRunner.class"
if not exist "%ROOT_DIR%att-engine\target\classes\att\Version.class" set "MISSING_CLASSES=%MISSING_CLASSES% att-engine/target/classes/att/Version.class"
if not exist "%ROOT_DIR%att-remote\target\classes\att\remote\RemoteCommand.class" set "MISSING_CLASSES=%MISSING_CLASSES% att-remote/target/classes/att/remote/RemoteCommand.class"
if not exist "%ROOT_DIR%att-server-api\target\classes\att\server\api\ServerApi.class" set "MISSING_CLASSES=%MISSING_CLASSES% att-server-api/target/classes/att/server/api/ServerApi.class"
if defined MISSING_CLASSES (
  echo ATT source checkout is missing required prebuilt classes:%MISSING_CLASSES% 1>&2
  echo Build explicitly with: mvn -DskipTests -pl att-cli -am compile 1>&2
  echo Or use the local package produced by the release build. 1>&2
  exit /b 2
)
if not defined M2_REPO set "M2_REPO=%USERPROFILE%\.m2\repository"
set "CP=%ROOT_DIR%att-cli\target\classes;%ROOT_DIR%att-engine\target\classes"
set "CP=%CP%;%ROOT_DIR%att-remote\target\classes;%ROOT_DIR%att-server-api\target\classes"
set "CP=%CP%;%M2_REPO%\commons-io\commons-io\2.16.1\commons-io-2.16.1.jar"
set "CP=%CP%;%M2_REPO%\org\apache\httpcomponents\httpclient\4.5.13\httpclient-4.5.13.jar"
set "CP=%CP%;%M2_REPO%\org\apache\httpcomponents\httpcore\4.4.13\httpcore-4.4.13.jar"
set "CP=%CP%;%M2_REPO%\commons-codec\commons-codec\1.11\commons-codec-1.11.jar"
set "CP=%CP%;%M2_REPO%\commons-logging\commons-logging\1.2\commons-logging-1.2.jar"
set "CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-annotations\2.17.2\jackson-annotations-2.17.2.jar"
set "CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-core\2.17.2\jackson-core-2.17.2.jar"
set "CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-databind\2.17.2\jackson-databind-2.17.2.jar"
set "CP=%CP%;%M2_REPO%\com\networknt\json-schema-validator\1.4.0\json-schema-validator-1.4.0.jar"
set "CP=%CP%;%M2_REPO%\com\zaxxer\HikariCP\4.0.3\HikariCP-4.0.3.jar"
set "CP=%CP%;%M2_REPO%\com\github\mwiede\jsch\2.28.2\jsch-2.28.2.jar"
set "CP=%CP%;%M2_REPO%\com\ethlo\time\itu\1.8.0\itu-1.8.0.jar"
set "CP=%CP%;%M2_REPO%\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"
set "CP=%CP%;%M2_REPO%\org\slf4j\slf4j-nop\2.0.9\slf4j-nop-2.0.9.jar"
set "CP=%CP%;%M2_REPO%\org\apache\commons\commons-compress\1.25.0\commons-compress-1.25.0.jar"
set "CP=%CP%;%M2_REPO%\org\apache\commons\commons-collections4\4.4\commons-collections4-4.4.jar"
set "CP=%CP%;%M2_REPO%\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar"
set "CP=%CP%;%M2_REPO%\org\apache\commons\commons-lang3\3.12.0\commons-lang3-3.12.0.jar"
set "CP=%CP%;%M2_REPO%\org\apache\xmlbeans\xmlbeans\5.2.0\xmlbeans-5.2.0.jar"
set "CP=%CP%;%M2_REPO%\org\apache\poi\poi-ooxml\5.2.5\poi-ooxml-5.2.5.jar"
set "CP=%CP%;%M2_REPO%\org\apache\poi\poi\5.2.5\poi-5.2.5.jar"
set "CP=%CP%;%M2_REPO%\org\apache\poi\poi-ooxml-lite\5.2.5\poi-ooxml-lite-5.2.5.jar"
set "CP=%CP%;%M2_REPO%\com\github\virtuald\curvesapi\1.08\curvesapi-1.08.jar"
set "CP=%CP%;%M2_REPO%\org\yaml\snakeyaml\2.2\snakeyaml-2.2.jar"
set "CP=%CP%;%M2_REPO%\org\apache\logging\log4j\log4j-api\2.21.1\log4j-api-2.21.1.jar"
set "CP=%CP%;%ROOT_DIR%lib\*"
java -Datt.home="%ATT_HOME%" -cp "%CP%" att.FrameworkRunner %*
exit /b %ERRORLEVEL%
:packaged
if defined ATT_PACKAGE_ROOT (
  cd /d "%ATT_PACKAGE_ROOT%" || exit /b 2
) else if exist "%ATT_HOME%\packages\default\" (
  cd /d "%ATT_HOME%\packages\default" || exit /b 2
)
set "CP=%APP_JAR%"
for %%F in ("%ATT_HOME%\lib\*.jar") do call :append_runtime_jar "%%~fF"
java -Datt.home="%ATT_HOME%" -cp "%CP%" att.FrameworkRunner %*
exit /b %ERRORLEVEL%
:select_app_jar
if not exist "%~1" exit /b 0
if defined APP_JAR (
  echo Multiple versioned ATT Local runtimes found under %ATT_HOME%\lib; keep one att-local-^<version^>.jar per ATT_HOME. 1>&2
  exit /b 2
)
set "APP_JAR=%~1"
exit /b 0
:append_runtime_jar
if not exist "%~1" exit /b 0
set "JAR_NAME=%~nx1"
if /i "%JAR_NAME:~0,10%"=="att-local-" exit /b 0
set "CP=%CP%;%~1"
exit /b 0
