@echo off
chcp 65001 >nul
if not exist out mkdir out
javac -encoding UTF-8 -d out src\fatfs\*.java
if errorlevel 1 (
  echo 编译失败
  pause
) else (
  echo 编译成功，运行 run.bat 启动程序
)
