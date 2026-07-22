@echo off
rem Author: Jeffrey + ChatGPT
setlocal EnableExtensions DisableDelayedExpansion

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%" || exit /b 2

where java >nul 2>&1
if errorlevel 1 (
  echo Java is required. Please install JDK 8 or newer. 1>&2
  exit /b 2
)

set "APP_JAR="
for %%F in ("%ROOT_DIR%lib\att-*.jar") do if exist "%%~fF" if not defined APP_JAR set "APP_JAR=%%~fF"
if defined APP_JAR goto packaged

rem Source-tree mode. Compile when Maven is available; otherwise use existing classes.
where mvn >nul 2>&1
if errorlevel 1 goto source_classes
echo Compiling ATT sources...
call mvn -q -DskipTests compile
if errorlevel 1 exit /b 1

:source_classes
if not exist "%ROOT_DIR%target\classes\att\FrameworkRunner.class" (
  echo Maven is required to compile ATT sources. Build a release package first or install Maven. 1>&2
  exit /b 2
)

if not defined M2_REPO set "M2_REPO=%USERPROFILE%\.m2\repository"
set "CP=%ROOT_DIR%target\classes;%ROOT_DIR%target\test-classes"
set "CP=%CP%;%M2_REPO%\commons-io\commons-io\2.16.1\commons-io-2.16.1.jar"
set "CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-annotations\2.17.2\jackson-annotations-2.17.2.jar"
set "CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-core\2.17.2\jackson-core-2.17.2.jar"
set "CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-databind\2.17.2\jackson-databind-2.17.2.jar"
set "CP=%CP%;%M2_REPO%\com\networknt\json-schema-validator\1.4.0\json-schema-validator-1.4.0.jar"
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

java -cp "%CP%" att.FrameworkRunner %*
exit /b %ERRORLEVEL%

:packaged
java -cp "%ROOT_DIR%lib\*" att.FrameworkRunner %*
exit /b %ERRORLEVEL%
