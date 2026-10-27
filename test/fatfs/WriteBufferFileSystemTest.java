package fatfs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 直接检查磁盘文件，验证满块刷新、关闭刷新及多个文件的待写数据隔离。 */
public class WriteBufferFileSystemTest {
    private static FileSystem fs;
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().toString())
                    .collect(Collectors.joining(File.pathSeparator));
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            Path directory = Files.createTempDirectory("fatfs-write-buffer-");
            Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                    WriteBufferFileSystemTest.class.getName(), "worker")
                    .directory(directory.toFile()).inheritIO().start();
            if (process.waitFor() != 0) throw new AssertionError("测试失败，磁盘保留于 " + directory);
            Files.deleteIfExists(directory.resolve("disk.dat"));
            Files.delete(directory);
            return;
        }

        fs = new FileSystem();
        command("create /a.tx 4");
        int first = entry("a.tx").startBlock;
        byte[] empty = raw(first);
        put("/a.tx", "x");
        check("不足一块时正文尚未落盘", Arrays.equals(raw(first), empty));
        put("/a.tx", "x".repeat(62));
        check("多次写入累积到 63 字节仍不落盘", Arrays.equals(raw(first), empty));
        put("/a.tx", "x");
        check("第 64 字节使整块落盘", Arrays.equals(raw(first), filled('x')));
        check("满块但没有多分配空块", fs.stats().used == 1);
        command("close /a.tx");
        check("满块关闭另存结束符", raw(fs.fatSnapshot()[first])[0] == '#');
        contents("a.tx", "x".repeat(64));

        for (int length : new int[]{0, 1, 63, 64, 65, 127, 128, 129, 190, 191, 192, 193}) {
            command("format");
            command("create /a.tx 4");
            put("/a.tx", "x".repeat(length));
            check("打开表按已接收字节计长", fs.openFiles().get(0).length == length);
            int block = entry("a.tx").startBlock;
            int remaining = length;
            do {
                check("只有完整块正文落盘: " + length,
                        Arrays.equals(raw(block), filled(remaining >= 64 ? 'x' : ' ')));
                remaining -= 64;
                block = fs.fatSnapshot()[block];
            } while (block != Disk.EOF);
            command("close /a.tx");
            contents("a.tx", "x".repeat(length));
            check("关闭后长度包含结束符块", entry("a.tx").lengthBlocks == (length + 64) / 64);
            command("delete /a.tx");
            check("删除回收全部盘块", fs.stats().free == 123);
        }

        command("format");
        command("create /a.tx 4");
        put("/a.tx", "p".repeat(37));
        command("close /a.tx");
        first = entry("a.tx").startBlock;
        byte[] original = raw(first);
        put("/a.TX", "q".repeat(26));
        check("追加不足块时保留旧盘块", Arrays.equals(raw(first), original));
        put("/a.tx", "r");
        check("追加填满时旧内容也正确保留", new String(raw(first), StandardCharsets.UTF_8)
                .equals("p".repeat(37) + "q".repeat(26) + "r"));
        put("/a.tx", "中文🙂");
        check("跨块余量留在内存", Arrays.equals(raw(fs.fatSnapshot()[first]), filled(' ')));
        command("close /a.tx");
        contents("a.tx", "p".repeat(37) + "q".repeat(26) + "r中文🙂");

        command("format");
        String[] names = {"a", "b", "c", "d", "e"};
        int[] lengths = {1, 17, 63, 64, 65};
        for (int i = 0; i < names.length; i++) {
            command("create /" + names[i] + ".tx 4");
            put("/" + names[i] + ".tx", names[i].repeat(lengths[i]));
        }
        command("md /dir"); // FAT、目录写入不能冲掉其他文件的待写数据
        command("dir /");
        for (String name : names) put("/" + name + ".TX", "tail");
        for (int i = names.length - 1; i >= 0; i--) {
            command("close /" + names[i] + ".tx");
            contents(names[i] + ".tx", names[i].repeat(lengths[i]) + "tail");
        }
        check("多文件交替关闭后表项全部释放", fs.openFiles().isEmpty());

        command("format");
        command("create /a.tx 4");
        first = entry("a.tx").startBlock;
        empty = raw(first);
        put("/a.tx", "中");
        command("create /b.tx 4");
        put("/b.tx", "other");
        command("close /b.tx");
        check("另一文件可正常读取", command("read /b.tx 10").contains("other"));
        command("close /b.tx");
        check("错误长度不会被接受", !fs.run("write /a.tx 1 文").ok);
        byte[] beforeZero = Files.readAllBytes(Path.of("disk.dat"));
        put("/a.tx", "");
        check("零字节写入不会提前刷新", Arrays.equals(beforeZero, Files.readAllBytes(Path.of("disk.dat"))));
        check("其他文件和错误命令不影响待写块", Arrays.equals(raw(first), empty));
        command("close /a.tx");
        contents("a.tx", "中");

        command("format");
        command("create /a.tx 4");
        put("/a.tx", "x".repeat(63));
        put("/a.tx", "中文🙂"); // UTF-8 字符可以横跨盘块
        command("close /a.tx");
        contents("a.tx", "x".repeat(63) + "中文🙂");

        command("format");
        command("create /a.tx 4");
        put("/a.tx", "discard");
        command("format");
        check("格式化清除带待写数据的打开表项", fs.openFiles().isEmpty());
        command("create /b.tx 4");
        put("/b.tx", "fresh");
        command("close /b.tx");
        contents("b.tx", "fresh");
        System.out.println("PASS: " + checks + " write-buffer checks; physical disk verified and isolated.");
    }

    private static void put(String path, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        check("接口成功接收字节", fs.write_file(path, bytes, bytes.length) == bytes.length);
    }

    private static FileSystem.EntryInfo entry(String name) throws Exception {
        return fs.listDirectory("/").stream().filter(e -> e.fullName.equals(name)).findFirst().orElseThrow();
    }

    private static byte[] raw(int block) throws Exception {
        return Arrays.copyOfRange(Files.readAllBytes(Path.of("disk.dat")), block * 64, (block + 1) * 64);
    }

    private static byte[] filled(char value) {
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void contents(String name, String expected) {
        check("关闭后完整读回 " + name, command("type /" + name).equals("文件内容: " + expected));
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
