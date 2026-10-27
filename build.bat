@echo off
rem Build Fleet Commander on Windows: compile src\ into jars\FleetCommander.jar, then validate the config.
rem Needs a JDK 17+ on PATH (the game bundles a JRE only). Set STARSECTOR_CORE if the mod is not in <game>\mods.
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "CORE=%STARSECTOR_CORE%"
if "%CORE%"=="" set "CORE=..\..\starsector-core"
if not exist "%CORE%\starfarer.api.jar" (
    echo error: starfarer.api.jar not found in "%CORE%". Set STARSECTOR_CORE to the folder that holds it.
    exit /b 1
)
for %%f in ("%CORE%\log4j-*.jar") do set "LOG4J=%%f"
set "CP=%CORE%\starfarer.api.jar;%CORE%\json.jar;%LOG4J%;%CORE%\lwjgl.jar;%CORE%\lwjgl_util.jar"

if exist build\classes rmdir /s /q build\classes
if exist build\tools rmdir /s /q build\tools
mkdir build\classes build\tools 2>nul
if not exist jars mkdir jars
dir /s /b src\*.java > build\sources.txt
javac --release 17 -encoding UTF-8 -cp "%CP%" -d build\classes @build\sources.txt || exit /b 1
jar cf jars\FleetCommander.jar -C build\classes . || exit /b 1
echo built jars\FleetCommander.jar

if "%1"=="--no-validate" exit /b 0
javac --release 17 -encoding UTF-8 -cp "build\classes;%CP%" -d build\tools tools\ValidateConfig.java || exit /b 1
java -cp "build\tools;build\classes;%CP%" ValidateConfig .
