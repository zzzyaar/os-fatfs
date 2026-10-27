package fatfs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 写长度接口回归检查，始终在子进程的独立目录中操作磁盘。 */
public class WriteLengthFileSystemTest {
    private static int checks;
    private static FileSystem fs;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().toString())
                    .collect(Collectors.joining(File.pathSeparator));
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            Path directory = Files.createTempDirectory("fatfs-write-length-");
            Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                    WriteLengthFileSystemTest.class.getName(), "worker")
                    .directory(directory.toFile()).inheritIO().start();
            if (process.waitFor() != 0) {
                throw new AssertionError("检查失败，测试磁盘保留于 " + directory);
            }
            Files.deleteIfExists(directory.resolve("disk.dat"));
            Files.delete(directory);
            return;
        }

        fs = new FileSystem();
        prefix("write", "abc", 2, "ab");
        prefix("write_file", "abc", 2, "ab");
        prefix("write", "hello world 123", 8, "hello wo");
        prefix("write", "abc 2", 5, "abc 2");
        prefix("write", "12345", 3, "123");
        prefix("write", "中文🙂", 3, "中");
        prefix("write_file", "中文🙂", 6, "中文");
        prefix("write", "中文🙂", 10, "中文🙂");
        prefix("write", "abc", 0, "");
        prefix("write", "", 0, "");
        for (int n : new int[]{63, 64, 65, 127, 128, 129}) {
            prefix("write", "x".repeat(n) + "ignored", n, "x".repeat(n));
            prefix("direct", "x".repeat(n) + "ignored", n, "x".repeat(n));
        }
        prefix("direct", "abc", 2, "ab");
        prefix("direct", "中文🙂", 6, "中文");
        prefix("direct", "", 0, "");
        directChecks();

        command("format");
        command("create /a.tx 4");
        command("write /a.tx 3 abcdef");
        for (boolean opened : new boolean[]{true, false}) {
            if (!opened) command("close /a.tx");
            for (String tail : new String[]{"-1 abc", "4 abc", "1 中文", "2 中文", "4 中🙂", "6 中🙂",
                    "NaN abc", "2147483648 abc", "2", "hello", "", "3.0 abc"}) {
                rejectUnchanged("write /a.tx " + tail);
            }
            rejectUnchanged("write_file /a.tx 2 中文");
        }
        command("write_file /a.TX 3 中文"); // 自动写打开，追加选定的字节前缀
        check("追加长度", fs.openFiles().get(0).length == 6);
        command("close /a.tx");
        check("追加内容", command("type /a.tx").equals("文件内容: abc中"));
        command("open /a.tx 0");
        rejectUnchanged("write /a.tx 1 xyz");
        command("close /a.tx");
        command("change /a.tx 1");
        rejectUnchanged("write /a.tx 1 xyz");
        command("md /dir");
        rejectUnchanged("write /dir 1 xyz");
        rejectUnchanged("write /no.tx 1 xyz");
        System.out.println("PASS: " + checks + " write-length checks; test disk isolated.");
    }

    private static void prefix(String verb, String text, int length, String expected) throws Exception {
        command("format");
        command("create /a.tx 4");
        if (verb.equals("direct")) {
            byte[] buffer = text.getBytes(StandardCharsets.UTF_8);
            byte[] before = buffer.clone();
            check("接口返回写入字节数", fs.write_file("/a.tx", buffer, length) == length);
            check("调用方缓冲不变", Arrays.equals(buffer, before));
        } else {
            check("反馈指定字节数", command(verb + " /a.tx " + length + " " + text)
                    .equals("已写入 " + length + " 字节到 /a.tx"));
        }
        check("打开表长度", fs.openFiles().get(0).length == length);
        command("close /a.tx");
        check("只写选定前缀", command("type /a.tx").equals("文件内容: " + expected));
        check("块数含结束符", fs.listDirectory("/").get(0).lengthBlocks == (length + 64) / 64);
        command("open /a.tx 0");
        check("重开后字节长度", fs.openFiles().get(0).length == expected.getBytes(StandardCharsets.UTF_8).length);
        check("读回选定前缀", command("read /a.tx 1000").equals("读出内容: " + expected + "\n（已遇到文件结束符 #）"));
        command("close /a.tx");
    }

    private static void directChecks() throws Exception {
        command("format");
        command("create /a.tx 4");
        for (boolean opened : new boolean[]{true, false}) {
            if (!opened) command("close /a.tx");
            rejectDirect("/a.tx", null, 0);
            rejectDirect("/a.tx", new byte[2], -1);
            rejectDirect("/a.tx", new byte[2], 3);
            rejectDirect("/a.tx", new byte[0], 1);
        }
        byte[] chinese = "中".getBytes(StandardCharsets.UTF_8);
        check("底层按字节写入且自动打开", fs.write_file("/a.TX", chinese, 1) == 1);
        check("底层可继续补齐字符", fs.write_file("/a.tx", Arrays.copyOfRange(chinese, 1, 3), 2) == 2);
        command("close /a.tx");
        check("分次字节写入结果", command("type /a.tx").equals("文件内容: 中"));
        command("open /a.tx 0");
        rejectDirect("/a.tx", chinese, 3);
        command("close /a.tx");
        command("change /a.tx 1");
        rejectDirect("/a.tx", chinese, 3);
        command("md /dir");
        rejectDirect("/dir", chinese, 3);
        rejectDirect("/no.tx", chinese, 3);
    }

    private static void rejectDirect(String path, byte[] buffer, int length) throws Exception {
        byte[] diskBefore = Files.readAllBytes(Path.of("disk.dat"));
        String tableBefore = tableState();
        boolean rejected = false;
        try {
            fs.write_file(path, buffer, length);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check("底层拒绝非法操作", rejected);
        check("底层拒绝后磁盘不变", Arrays.equals(diskBefore, Files.readAllBytes(Path.of("disk.dat"))));
        check("底层拒绝后打开表不变", tableBefore.equals(tableState()));
    }

    private static void rejectUnchanged(String line) throws Exception {
        byte[] diskBefore = Files.readAllBytes(Path.of("disk.dat"));
        String tableBefore = tableState();
        check("应拒绝: " + line, !fs.run(line).ok);
        check("拒绝后磁盘不变: " + line, Arrays.equals(diskBefore, Files.readAllBytes(Path.of("disk.dat"))));
        check("拒绝后打开表不变: " + line, tableBefore.equals(tableState()));
    }

    private static String tableState() {
        return fs.openFiles().stream().map(e -> e.name + ":" + e.length + ":" + e.flag + ":"
                + e.readDnum + ":" + e.readBnum + ":" + e.writeDnum + ":" + e.writeBnum)
                .collect(Collectors.joining(";"));
    }

    private static String command(String line) {
        FileSystem.Result result = fs.run(line);
        if (!result.ok) throw new AssertionError(line + ": " + result.text);
        return result.text;
    }

    private static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
