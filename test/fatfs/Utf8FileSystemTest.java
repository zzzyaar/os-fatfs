package fatfs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Standalone regression checks; launches a child JVM with an isolated disk.dat. */
public class Utf8FileSystemTest {
    private static int checks;
    private static FileSystem fs;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().toString())
                    .collect(Collectors.joining(File.pathSeparator));
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            Path directory = Files.createTempDirectory("fatfs-utf8-");
            Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                    Utf8FileSystemTest.class.getName(), "worker")
                    .directory(directory.toFile()).inheritIO().start();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new AssertionError("Checks failed; test disk retained at " + directory);
            }
            Files.deleteIfExists(directory.resolve("disk.dat"));
            Files.delete(directory);
            return;
        }

        fs = new FileSystem();
        for (String text : new String[]{"hello world", "中文", "Hello中文，🙂é", "中".repeat(22),
                "中".repeat(21), "中".repeat(21) + "a", "中".repeat(21) + "ab",
                "a".repeat(62) + "中文🙂", "a".repeat(63) + "中文🙂", "a".repeat(64) + "中文🙂"}) {
            roundTrip(text);
        }

        command("format");
        command("create /a.tx 4");
        command("close /a.tx");
        check("empty file display", command("type /a.tx").equals("文件内容: "));
        check("empty file read", read("/a.tx", 1).isEmpty());
        check("repeated empty file read", read("/a.tx", 1).isEmpty());
        command("close /a.tx");

        command("format");
        create("/a.tx", "中");
        create("/b.tx", "文");
        check("first partial character", read("/a.tx", 1).equals(""));
        check("second file partial character", read("/b.tx", 2).equals(""));
        check("pending bytes are per file", read("/a.tx", 2).equals("中"));
        check("second file completes independently", read("/b.tx", 1).equals("文"));
        command("close /a.tx");
        command("close /b.tx");
        check("start another partial read", read("/a.tx", 1).equals(""));
        command("close /a.tx");
        check("reopening resets pending bytes and pointer", read("/a.tx", 3).equals("中"));
        command("close /a.tx");

        command("write /a.tx 文🙂");
        check("append byte length", fs.openFiles().get(0).length == 10);
        command("close /a.tx");
        check("append keeps original text", command("type /a.tx").equals("文件内容: 中文🙂"));
        check("read appended text", read("/a.tx", 100).equals("中文🙂"));
        command("close /a.tx");

        command("format");
        create("/a.tx", "中文#后面");
        check("type keeps EOF marker semantics", command("type /a.tx").equals("文件内容: 中文"));
        check("read keeps EOF marker semantics", read("/a.tx", 100).equals("中文"));
        System.out.println("PASS: " + checks + " UTF-8 regression checks; test disk isolated.");
    }

    private static void roundTrip(String text) {
        command("format");
        command("create /a.tx 4");
        int byteLength = text.getBytes(StandardCharsets.UTF_8).length;
        check("write reports UTF-8 byte length", command("write /a.tx " + text)
                .equals("已写入 " + byteLength + " 字节到 /a.tx"));
        check("open table counts bytes", fs.openFiles().get(0).length == byteLength);
        command("close /a.tx");
        check("type round trip", command("type /a.tx").equals("文件内容: " + text));
        try {
            check("directory length includes EOF block", fs.listDirectory("/").get(0).lengthBlocks
                    == (byteLength + 1 + Disk.BLOCK_SIZE - 1) / Disk.BLOCK_SIZE);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        command("open /a.tx 0");
        check("reopened length counts bytes", fs.openFiles().get(0).length == byteLength);
        StringBuilder result = new StringBuilder();
        int[] sizes = {1, 2, 1, 4, 63, 2, 64};
        int remaining = byteLength;
        int index = 0;
        while (remaining > 0) {
            int size = Math.min(remaining, sizes[index++ % sizes.length]);
            result.append(read("/a.tx", size));
            remaining -= size;
        }
        check("chunked reads reproduce text", result.toString().equals(text));
        check("read stops at EOF", read("/a.tx", 100).isEmpty());
        command("close /a.tx");
    }

    private static void create(String path, String text) {
        command("create " + path + " 4");
        command("write " + path + " " + text);
        command("close " + path);
    }

    private static String read(String path, int bytes) {
        String result = command("read " + path + " " + bytes);
        String prefix = "读出内容: ";
        check("read output prefix", result.startsWith(prefix));
        return result.substring(prefix.length()).replace("\n（已遇到文件结束符 #）", "");
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
