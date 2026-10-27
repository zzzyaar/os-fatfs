package fatfs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 空间不足时整次拒绝，并确保成功写入的文件始终可以关闭和回收。 */
public class DiskFullFileSystemTest {
    private static FileSystem fs;
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().toString())
                    .collect(Collectors.joining(File.pathSeparator));
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            Path directory = Files.createTempDirectory("fatfs-disk-full-");
            Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                    DiskFullFileSystemTest.class.getName(), "worker")
                    .directory(directory.toFile()).inheritIO().start();
            if (process.waitFor() != 0) throw new AssertionError("测试失败，磁盘保留于 " + directory);
            Files.deleteIfExists(directory.resolve("disk.dat"));
            Files.delete(directory);
            return;
        }

        fs = new FileSystem();
        command("create /a.tx 4");
        rejectSpace("/a.tx", "x".repeat(7872));
        rejectSpace("/a.tx", "x".repeat(8000));
        check("超量写后没有消耗盘块", fs.stats().free == 122);
        command("close /a.tx");
        contents("a.tx", "");
        command("delete /a.tx");
        check("失败后文件可正常回收", fs.stats().free == 123);

        command("create /a.tx 4");
        put("/a.tx", "中文");
        rejectSpace("/a.tx", "🙂".repeat(2000));
        put("/a.tx", "保留");
        command("close /a.tx");
        contents("a.tx", "中文保留");

        for (String payload : new String[]{"x".repeat(7871), "中".repeat(2623) + "ab"}) {
            command("format");
            command("create /a.tx 4");
            put("/a.tx", payload);
            check("最大正文消耗所有数据盘块", fs.stats().free == 0);
            check("打开表记录全部正文字节", fs.openFiles().get(0).length == 7871);
            check("目录块数与 FAT 一致", fs.listDirectory("/").get(0).lengthBlocks == 123);
            rejectSpace("/a.tx", "z");
            command("close /a.tx");
            contents("a.tx", payload);
            rejectSpace("/a.TX", "z"); // 自动打开前拒绝，不留下新的打开表项
            check("失败不会打开已关闭文件", fs.openFiles().isEmpty());
            check("满盘文件仍可读", command("read /a.tx 8000").equals("读出内容: " + payload + "\n（已遇到文件结束符 #）"));
            command("close /a.tx");
            put("/a.tx", ""); // 0 字节无需另分配 EOF 块
            command("close /a.tx");
            command("delete /a.tx");
            check("最大文件可删除并回收全部盘块", fs.stats().free == 123);
        }

        for (int firstLength : new int[]{0, 63, 64, 65, 127, 128}) {
            command("format");
            command("create /a.tx 4");
            put("/a.tx", "a".repeat(firstLength));
            int firstBlocks = (firstLength + 64) / 64;
            check("EOF 所需盘块已属于文件链", fs.listDirectory("/").get(0).lengthBlocks == firstBlocks);
            check("预留 EOF 后的可用空间", fs.stats().free == 123 - firstBlocks);
            command("create /b.tx 4");
            int secondLength = (123 - firstBlocks) * 64 - 1;
            put("/b.tx", "b".repeat(secondLength));
            check("其他文件耗尽剩余空间", fs.stats().free == 0);
            byte[] diskBefore = Files.readAllBytes(Path.of("disk.dat"));
            check("满盘不能再建文件", !fs.run("create /c.tx 4").ok);
            check("满盘不能再建目录", !fs.run("md /dir").ok);
            check("失败的创建不改变磁盘", Arrays.equals(diskBefore, Files.readAllBytes(Path.of("disk.dat"))));
            command("close /a.tx"); // 其他文件不能抢走它关闭所需的空间
            command("close /b.tx");
            contents("a.tx", "a".repeat(firstLength));
            contents("b.tx", "b".repeat(secondLength));
            command("delete /a.tx");
            command("delete /b.tx");
            check("两个文件均可回收", fs.stats().free == 123);
        }

        command("format");
        command("create /a.tx 4");
        put("/a.tx", "a".repeat(60));
        command("create /b.tx 4");
        put("/b.tx", "b".repeat(122 * 64 - 1));
        command("close /b.tx");
        check("满盘但本文件仍有块内余量", fs.stats().free == 0);
        put("/a.tx", "中"); // 正文填到 63，最后一字节留给 EOF
        rejectSpace("/a.tx", "z");
        command("close /a.tx");
        contents("a.tx", "a".repeat(60) + "中");
        command("delete /b.tx");
        put("/a.tx", "z"); // 释放空间后重试，跨过 64 字节边界
        command("close /a.tx");
        contents("a.tx", "a".repeat(60) + "中z");
        check("再次跨块写入已预留 EOF 块", fs.listDirectory("/").get(0).lengthBlocks == 2);

        command("format");
        command("create /a.tx 4");
        FileSystem.Result result = fs.run("write_file /a.tx 8000 " + "x".repeat(8000));
        check("命令返回普通空间错误", !result.ok && result.text.contains("磁盘空间不足"));
        check("命令失败没有部分正文", fs.openFiles().get(0).length == 0);
        byte[] largeBuffer = new byte[8000];
        Arrays.fill(largeBuffer, (byte) 'q');
        check("容量检查使用写长度而非整个缓冲长度", fs.write_file("/a.tx", largeBuffer, 3) == 3);
        command("close /a.tx");
        contents("a.tx", "qqq");
        System.out.println("PASS: " + checks + " disk-full checks; EOF space and failed-write state verified.");
    }

    private static void rejectSpace(String path, String text) throws Exception {
        byte[] diskBefore = Files.readAllBytes(Path.of("disk.dat"));
        String tableBefore = tableState();
        boolean rejected = false;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            fs.write_file(path, bytes, bytes.length);
        } catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("磁盘空间不足");
        }
        check("拒绝空间不足的整次写入", rejected);
        check("拒绝后磁盘字节不变", Arrays.equals(diskBefore, Files.readAllBytes(Path.of("disk.dat"))));
        check("拒绝后打开表及待写数据不变", tableBefore.equals(tableState()));
    }

    private static String tableState() {
        return fs.openFiles().stream().map(e -> e.name + ":" + e.length + ":" + e.flag + ":"
                + e.readDnum + ":" + e.readBnum + ":" + e.writeDnum + ":" + e.writeBnum
                + ":" + Arrays.toString(e.writeBuffer)).collect(Collectors.joining(";"));
    }

    private static void put(String path, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        check("成功接收全部请求字节", fs.write_file(path, bytes, bytes.length) == bytes.length);
    }

    private static void contents(String name, String expected) {
        check("文件内容正确: " + name, command("type /" + name).equals("文件内容: " + expected));
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
