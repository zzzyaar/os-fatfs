@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\fatfs\*.java
if errorlevel 1 (
  echo 编译失败，请检查上方错误信息。
  pause
  exit /b 1
)
echo 编译成功，运行 run.bat 启动程序。
pause
exit /b 0
