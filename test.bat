@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\coinmetrics\*.java tests\coinmetrics\*.java
if errorlevel 1 exit /b 1
java -cp out coinmetrics.CoreTests
