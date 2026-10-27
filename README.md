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
write /doc/a.tx 5 hello
close /doc/a.tx
type /doc/a.tx
dir /doc
```

最后可以看到文件内容 `hello` 和目录中的 `a.tx`。输入 `help` 查看全部命令。文件主名最多 3 字节、类型名最多 2 字节，名称使用 ASCII；正文支持 UTF-8 中文，例如 `write /doc/a.tx 12 中文测试`。

写文件命令为 `write <路径> <写入字节数> <内容>`，也可使用别名 `write_file`。字节数放在内容前，便于正文包含空格。例如 `write /doc/a.tx 2 abc` 只追加 `ab`，`write /doc/a.tx 3 中文` 只追加 `中`。字节数必须在 0 到正文的 UTF-8 字节数之间，且不能截断一个字符；写入 0 字节可省略内容。右键“写文件”会依次询问正文和写入字节数。旧格式 `write 路径 内容` 不再支持，原有命令需补上字节数。

代码中，`write(String arg)` 负责解析命令、将正文编码为 UTF-8 并检查字符边界，再调用 `write_file(String path, byte[] buffer, int len)` 执行实际写入。后者按“文件名、缓冲、写长度”接收参数，仅按字节处理，成功时返回写入字节数。此处的参数缓冲是待写数据，与磁盘内部的盘块缓冲用途不同。

写文件时，每个打开的写文件各自保留一个未满的 64 字节盘块；填满后整块写盘，关闭时补上 `#` 并写回剩余数据。多次写入可连续填充同一块，多个文件交替写入时互不混用待写数据。写入成功表示数据已被接收，关闭文件前请勿直接退出程序。

每次写入前会检查正文和结束符 `#` 所需的总空间；容量不足时整次拒绝，不写入部分正文。正文恰好填满盘块时，会提前分配下一块供结束符使用，防止被其他文件占用。空磁盘的 123 个可分配盘块共 7872 字节，单文件正文最多 7871 字节，剩余 1 字节存放结束符。

读写长度按 UTF-8 字节数计算，例如“中文”占 6 字节。`read` 如果在一个多字节字符中间停止，未完整的字节会暂存，后续读取完整后再显示。关闭并重新以读方式打开文件，会从头开始读取。

文件主名和目录名区分大小写，后缀统一按小写存储和识别。例如 `a.tx` 与 `A.tx` 是不同文件，`a.TX` 与 `a.tx` 是同一文件；`/Doc` 与 `/doc` 是不同目录。后缀仍最多 2 字节，不能使用 `.txt`。

## 读写检查

在项目目录执行：

```powershell
javac -encoding UTF-8 -d out src\fatfs\*.java test\fatfs\*.java
java '-Dfile.encoding=UTF-8' -cp out fatfs.Utf8FileSystemTest
java '-Dfile.encoding=UTF-8' -cp out fatfs.WriteLengthFileSystemTest
java '-Dfile.encoding=UTF-8' -cp out fatfs.WriteBufferFileSystemTest
java '-Dfile.encoding=UTF-8' -cp out fatfs.DiskFullFileSystemTest
```

测试在独立临时目录中运行，检查中文、表情符号、跨块及分次读取、追加、字节长度和空文件。
写长度检查另覆盖指定前缀、带空格及数字的正文、零字节写入、非法长度拒绝后磁盘和打开表不变，以及读模式和只读保护。
写缓冲检查直接读取模拟磁盘，验证不足一块时暂存、满块刷新、关闭刷新、追加、跨块、多个文件交替写入和格式化清除待写状态。
满盘检查覆盖结束符空间预留、最大正文、超量写拒绝后状态不变、多个文件占满磁盘后关闭和回收、块内余量及释放空间后重试。

## 文件说明

- `src/fatfs/`：文件系统实现及 Java Swing 图形界面。
- `test/fatfs/`：独立运行的中文读写、写长度、写缓冲和满盘回归检查。
- `build.bat`、`run.bat`：队友提供的原始脚本。
- `doc/`：与队友共享的文档。
- `docbymyself/`：本地私用文档，已加入 Git 忽略规则。
- `.gitignore`：排除私用文档、编译产物、运行时磁盘及本机 IDE 配置。

老师发放的资料、PDF 提取笔记和本机试运行日志保留在课程设计工作目录中，本仓库暂未包含这些材料。
