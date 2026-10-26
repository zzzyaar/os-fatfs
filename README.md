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

最后可以看到文件内容 `hello` 和目录中的 `a.tx`。输入 `help` 查看全部命令。文件主名最多 3 字节、类型名最多 2 字节，名称使用 ASCII；正文支持 UTF-8 中文，例如 `write /doc/a.tx 中文测试`。

读写长度按 UTF-8 字节数计算，例如“中文”占 6 字节。`read` 如果在一个多字节字符中间停止，未完整的字节会暂存，后续读取完整后再显示。关闭并重新以读方式打开文件，会从头开始读取。

文件主名和目录名区分大小写，后缀统一按小写存储和识别。例如 `a.tx` 与 `A.tx` 是不同文件，`a.TX` 与 `a.tx` 是同一文件；`/Doc` 与 `/doc` 是不同目录。后缀仍最多 2 字节，不能使用 `.txt`。

## 中文读写检查

在项目目录执行：

```powershell
javac -encoding UTF-8 -d out src\fatfs\*.java test\fatfs\Utf8FileSystemTest.java
java '-Dfile.encoding=UTF-8' -cp out fatfs.Utf8FileSystemTest
```

测试在独立临时目录中运行，检查中文、表情符号、跨块及分次读取、追加、字节长度和空文件。

## 文件说明

- `src/fatfs/`：文件系统实现及 Java Swing 图形界面。
- `test/fatfs/`：独立运行的中文读写回归检查。
- `build.bat`、`run.bat`：队友提供的原始脚本。
- `doc/`：与队友共享的文档。
- `docbymyself/`：本地私用文档，已加入 Git 忽略规则。
- `.gitignore`：排除私用文档、编译产物、运行时磁盘及本机 IDE 配置。

老师发放的资料、PDF 提取笔记和本机试运行日志保留在课程设计工作目录中，本仓库暂未包含这些材料。
