@echo off
if not exist out mkdir out
javac -d out src\sportground\*.java
if errorlevel 1 exit /b 1
java -cp out sportground.App
