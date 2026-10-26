# 模拟磁盘文件系统

操作系统课程设计第五题，使用 Java Swing 实现图形界面，使用 FAT 管理模拟磁盘。

## 编译与运行

需要安装 JDK，并确保 `java`、`javac` 可以在命令行中使用。本机已使用 JDK 17 编译并启动。

在本目录打开 PowerShell 或命令提示符，执行：

```powershell
javac -encoding UTF-8 -d out src\fatfs\*.java
java -cp out fatfs.Main
```

程序每次启动都会重新初始化当前目录下的 `disk.dat`，已有模拟文件会被清空。请避免同时启动多个程序操作同一磁盘文件。

也可以先双击 `build.bat` 编译，看到成功提示后按任意键关闭窗口，再双击 `run.bat` 启动程序。编译失败或程序启动报错时，窗口会保留错误信息，按任意键后关闭。

## 简单试用

在程序右侧命令输入框中依次执行：

```text
md /doc
create /doc/a.tx 4
write /doc/a.tx hello
close /doc/a.tx
type /doc/a.tx
dir /doc
```

最后可以看到文件内容 `hello` 和目录中的 `a.tx`。输入 `help` 查看全部命令。文件主名最多 3 字节、类型名最多 2 字节，试用时先使用 ASCII 名称及正文。

## 文件说明

- `src/fatfs/`：队友提供的原始 Java 源码。
- `build.bat`、`run.bat`：队友提供的原始脚本。
- `doc/`：与队友共享的文档。
- `docbymyself/`：本地私用文档，已加入 Git 忽略规则。
- `.gitignore`：排除私用文档、编译产物、运行时磁盘及本机 IDE 配置。

老师发放的资料、PDF 提取笔记和本机试运行日志保留在课程设计工作目录中，本仓库暂未包含这些材料。
