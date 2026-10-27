package fatfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 模拟磁盘文件系统核心：命令解析 + 文件/目录操作。
 * 只支持绝对路径（题目要求），路径分隔符为 '/'。
 */
public class FileSystem {
    public static final char EOF_CHAR = '#';

    private final Disk disk;
    private final OpenFileTable openFileTable = new OpenFileTable();

    public FileSystem() throws IOException {
        disk = new Disk("disk.dat");
    }

    /** 命令执行结果：ok 表示成功与否，text 为输出文本 */
    public static final class Result {
        public final boolean ok;
        public final String text;

        Result(boolean ok, String text) {
            this.ok = ok;
            this.text = text == null ? "" : text;
        }
    }

    /** 执行一行命令，返回输出文本（不抛异常，错误以文本形式返回） */
    public String execute(String line) {
        return run(line).text;
    }

    /** 执行一行命令，同时返回是否成功（界面据此决定输出颜色） */
    public Result run(String line) {
        String cmdLine = line == null ? "" : line.trim();
        if (cmdLine.isEmpty()) {
            return new Result(true, "");
        }
        String[] parts = cmdLine.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case "md":          return new Result(true, md(arg));
                case "dir":         return new Result(true, dir(arg));
                case "rd":          return new Result(true, rd(arg));
                case "create":
                case "create_file": return new Result(true, create(arg));
                case "open":
                case "open_file":   return new Result(true, open(arg));
                case "read":
                case "read_file":   return new Result(true, read(arg));
                case "write":
                case "write_file":  return new Result(true, write(arg));
                case "close":
                case "close_file":  return new Result(true, close(arg));
                case "delete":
                case "delete_file": return new Result(true, delete(arg));
                case "type":
                case "typefile":    return new Result(true, type(arg));
                case "change":      return new Result(true, change(arg));
                case "format":      return new Result(true, format());
                case "help":        return new Result(true, help());
                default:
                    return new Result(false, "未知命令: " + cmd + "\n" + help());
            }
        } catch (IOException e) {
            return new Result(false, "磁盘 I/O 错误: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return new Result(false, "操作失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 命令实现
    // ------------------------------------------------------------------

    /** 建立目录：md /a/b */
    private String md(String arg) throws IOException {
        String[] segs = splitPath(arg);
        if (segs.length == 0) {
            throw new IllegalArgumentException("根目录已存在，不能重复建立");
        }
        String name = segs[segs.length - 1];
        parseDirName(name);

        int parent = traverse(segs, segs.length - 1);
        if (findInBlock(parent, name) >= 0) {
            throw new IllegalArgumentException("同名文件或目录已存在: " + name);
        }
        int idx = findEmptyIndex(parent);
        if (idx < 0) {
            throw new IllegalArgumentException("目录已满（最多 8 项）");
        }
        int block = disk.allocBlock();
        if (block < 0) {
            throw new IllegalArgumentException("磁盘空间不足");
        }
        disk.writeBlock(block, Disk.emptyDirBlock());

        DirEntry entry = DirEntry.dir(name, block);
        putEntry(parent, idx, entry);
        return "目录已建立: " + arg;
    }

    /** 列目录：dir 或 dir /a */
    private String dir(String arg) throws IOException {
        int block = Disk.ROOT_BLOCK;
        String shown = "/";
        if (!arg.isEmpty() && !arg.equals("/")) {
            String[] segs = splitPath(arg);
            block = traverse(segs, segs.length);
            shown = arg;
        }
        StringBuilder sb = new StringBuilder("目录 ").append(shown).append(" 的内容:\n");
        byte[] data = disk.readBlock(block);
        int count = 0;
        for (int i = 0; i < Disk.BLOCK_SIZE; i += DirEntry.SIZE) {
            DirEntry e = new DirEntry(data, i);
            if (!e.isEmpty()) {
                sb.append("  ").append(e.describe()).append("\n");
                count++;
            }
        }
        if (count == 0) {
            sb.append("  （空目录）\n");
        }
        return sb.toString();
    }

    /** 删除空目录：rd /a/b */
    private String rd(String arg) throws IOException {
        Lookup l = lookup(arg);
        if (!l.ok) {
            throw new IllegalArgumentException(l.error);
        }
        if (!l.entry.isDirectory()) {
            throw new IllegalArgumentException(arg + " 不是目录，请用 delete");
        }
        if (l.entry.startBlock() == Disk.ROOT_BLOCK) {
            throw new IllegalArgumentException("根目录不能删除");
        }
        byte[] data = disk.readBlock(l.entry.startBlock());
        for (int i = 0; i < Disk.BLOCK_SIZE; i += DirEntry.SIZE) {
            DirEntry e = new DirEntry(data, i);
            if (!e.isEmpty()) {
                throw new IllegalArgumentException("目录非空，不能删除");
            }
        }
        disk.freeChain(l.entry.startBlock());
        clearEntry(l);
        return "空目录已删除: " + arg;
    }

    /** 建立文件：create /a.txt 4 */
    private String create(String arg) throws IOException {
        String[] parts = arg.split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("用法: create <绝对路径文件名> <属性>");
        }
        String path = FileNames.normalizeExtension(parts[0]);
        int attr = parseFileAttribute(parts[1], "属性");
        if ((attr & DirEntry.ATTR_READONLY) != 0) {
            throw new IllegalArgumentException("只读文件不能建立");
        }

        String[] segs = splitPath(path);
        String last = segs[segs.length - 1];
        String[] nameExt = parseFileName(last, true);

        int parent = traverse(segs, segs.length - 1);
        if (findInBlock(parent, last) >= 0) {
            throw new IllegalArgumentException("文件已存在: " + last);
        }
        int idx = findEmptyIndex(parent);
        if (idx < 0) {
            throw new IllegalArgumentException("父目录已满（最多 8 项）");
        }
        if (openFileTable.size() >= OpenFileTable.MAX) {
            throw new IllegalArgumentException("已打开文件表已满（最多 5 个）");
        }

        int block = disk.allocBlock();
        if (block < 0) {
            throw new IllegalArgumentException("磁盘空间不足");
        }
        byte[] blank = new byte[Disk.BLOCK_SIZE];
        Arrays.fill(blank, (byte) ' ');
        disk.writeBlock(block, blank);

        DirEntry entry = DirEntry.file(nameExt[0], nameExt[1], attr, block, 1);
        putEntry(parent, idx, entry);

        OFTLE oftle = new OFTLE(path, attr, block, 0, OFTLE.WRITE,
                block, 0, block, 0);
        openFileTable.add(oftle);
        return "文件已建立并打开（写方式）: " + path;
    }

    /** 打开文件：open /a.txt 0（0 读，1 写） */
    private String open(String arg) throws IOException {
        String[] parts = arg.split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("用法: open <绝对路径文件名> <0读|1写>");
        }
        String path = FileNames.normalizeExtension(parts[0]);
        int flag = parseByte(parts[1], "操作类型");
        if (flag != OFTLE.READ && flag != OFTLE.WRITE) {
            throw new IllegalArgumentException("操作类型只能为 0（读）或 1（写）");
        }

        Lookup l = lookup(path);
        if (!l.ok) {
            throw new IllegalArgumentException(l.error);
        }
        if (l.entry.isDirectory()) {
            throw new IllegalArgumentException(path + " 是目录，不能打开");
        }
        if (flag == OFTLE.WRITE && (l.entry.attr() & DirEntry.ATTR_READONLY) != 0) {
            throw new IllegalArgumentException("不能以写方式打开只读文件");
        }
        if (openFileTable.isOpen(path)) {
            return "文件已打开: " + path;
        }
        if (openFileTable.size() >= OpenFileTable.MAX) {
            throw new IllegalArgumentException("已打开文件表已满（最多 5 个）");
        }

        EofPos eof = findEof(l.entry.startBlock());
        OFTLE oftle = new OFTLE(path, l.entry.attr(), l.entry.startBlock(), eof.bytes,
                flag, l.entry.startBlock(), 0, eof.dnum, eof.bnum);
        openFileTable.add(oftle);
        return "文件已打开: " + path + "（" + oftle.mode() + "方式，长度 " + eof.bytes + " 字节）";
    }

    /** 读文件：read /a.txt 10 */
    private String read(String arg) throws IOException {
        String[] parts = arg.split("\\s+", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("用法: read <绝对路径文件名> <读取长度>");
        }
        String path = parts[0];
        int len = parseInt(parts[1], "读取长度");
        if (len <= 0) {
            throw new IllegalArgumentException("读取长度必须为正数");
        }

        Lookup l = lookup(path);
        if (!l.ok) {
            throw new IllegalArgumentException(l.error);
        }
        if (l.entry.isDirectory()) {
            throw new IllegalArgumentException(path + " 是目录，不能读");
        }
        if (!openFileTable.isOpen(path)) {
            open(path + " 0");
        }

        OFTLE oftle = openFileTable.get(path);
        if (oftle.flag != OFTLE.READ) {
            throw new IllegalArgumentException("文件以写方式打开，不能读");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(oftle.readPendingBytes, 0, oftle.readPendingBytes.length);
        boolean eofHit = false;
        for (int n = 0; n < len; n++) {
            byte[] data = disk.readBlock(oftle.readDnum);
            if (data[oftle.readBnum] == EOF_CHAR) {
                eofHit = true;
                break;
            }
            bytes.write(data[oftle.readBnum] & 0xFF);
            oftle.readBnum++;
            if (oftle.readBnum >= Disk.BLOCK_SIZE) {
                int next = disk.nextBlock(oftle.readDnum);
                if (next == -1) {
                    break;
                }
                oftle.readDnum = next;
                oftle.readBnum = 0;
            }
        }
        ByteBuffer encoded = ByteBuffer.wrap(bytes.toByteArray());
        CharBuffer decoded = CharBuffer.allocate(encoded.remaining());
        CoderResult result = StandardCharsets.UTF_8.newDecoder().decode(encoded, decoded, eofHit);
        if (result.isError()) {
            throw new IllegalArgumentException("文件内容不是有效的 UTF-8 文本");
        }
        oftle.readPendingBytes = new byte[encoded.remaining()];
        encoded.get(oftle.readPendingBytes);
        decoded.flip();
        return "读出内容: " + decoded + (eofHit ? "\n（已遇到文件结束符 #）" : "");
    }

    /** 写文件：write /a.tx 2 abc（写入正文缓冲的前 2 字节） */
    private String write(String arg) throws IOException {
        String[] parts = arg.split("\\s+", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("用法: write <绝对路径文件名> <写入字节数> <内容>");
        }
        String path = parts[0];
        int len = parseInt(parts[1], "写入字节数");
        String content = parts.length == 3 ? parts[2] : "";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (len >= 0 && len < bytes.length && (bytes[len] & 0xC0) == 0x80) {
            throw new IllegalArgumentException("写入字节数不能截断 UTF-8 字符，请选择完整字符的字节边界");
        }
        int written = write_file(path, bytes, len);
        return "已写入 " + written + " 字节到 " + path;
    }

    /** 将缓冲的前 len 字节写入文件，成功时返回写入字节数；不解释文本编码。 */
    public int write_file(String path, byte[] buffer, int len) throws IOException {
        if (buffer == null) {
            throw new IllegalArgumentException("写入缓冲不能为 null");
        }
        if (len < 0 || len > buffer.length) {
            throw new IllegalArgumentException("写入字节数必须在 0~" + buffer.length + " 之间（缓冲的字节数）");
        }

        Lookup l = lookup(path);
        if (!l.ok) {
            throw new IllegalArgumentException(l.error);
        }
        if (l.entry.isDirectory()) {
            throw new IllegalArgumentException(path + " 是目录，不能写");
        }
        if (!openFileTable.isOpen(path)) {
            open(path + " 1");
        }

        OFTLE oftle = openFileTable.get(path);
        if (oftle.flag != OFTLE.WRITE) {
            throw new IllegalArgumentException("文件不是写方式打开，不能写");
        }

        for (int i = 0; i < len; i++) {
            // 当前块写满时，仅当还有下一字节要写才推进/申请下一块
            if (oftle.writeBnum >= Disk.BLOCK_SIZE) {
                int next = disk.nextBlock(oftle.writeDnum);
                if (next == -1) {
                    next = disk.allocBlock();
                    if (next < 0) {
                        throw new IllegalArgumentException("磁盘空间不足");
                    }
                    disk.setFat(oftle.writeDnum, next);
                    byte[] blank = new byte[Disk.BLOCK_SIZE];
                    Arrays.fill(blank, (byte) ' ');
                    disk.writeBlock(next, blank);
                }
                oftle.writeDnum = next;
                oftle.writeBnum = 0;
            }
            if (oftle.writeBuffer == null) {
                oftle.writeBuffer = disk.readBlock(oftle.writeDnum);
            }
            oftle.writeBuffer[oftle.writeBnum] = buffer[i];
            if (oftle.writeBnum == Disk.BLOCK_SIZE - 1) {
                disk.writeBlock(oftle.writeDnum, oftle.writeBuffer);
                oftle.writeBuffer = null;
            }

            oftle.writeBnum++;
            oftle.length++;
        }

        int blocks = disk.countChainBlocks(oftle.number);
        if (blocks > 255) {
            blocks = 255;
        }
        l.entry.setLengthBlocks(blocks);
        writeEntry(l);

        return len;
    }

    /** 关闭文件：close /a.txt */
    private String close(String arg) throws IOException {
        String path = arg.trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("用法: close <绝对路径文件名>");
        }
        if (!openFileTable.isOpen(path)) {
            return "文件未打开，无需关闭: " + path;
        }

        OFTLE oftle = openFileTable.get(path);
        if (oftle.flag == OFTLE.WRITE) {
            // 若写指针恰好在盘块末尾，先申请/推进到下一块
            if (oftle.writeBnum >= Disk.BLOCK_SIZE) {
                int next = disk.nextBlock(oftle.writeDnum);
                if (next == -1) {
                    next = disk.allocBlock();
                    if (next < 0) {
                        throw new IllegalArgumentException("磁盘空间不足");
                    }
                    disk.setFat(oftle.writeDnum, next);
                    byte[] blank = new byte[Disk.BLOCK_SIZE];
                    Arrays.fill(blank, (byte) ' ');
                    disk.writeBlock(next, blank);
                }
                oftle.writeDnum = next;
                oftle.writeBnum = 0;
            }
            byte[] data = oftle.writeBuffer != null ? oftle.writeBuffer : disk.readBlock(oftle.writeDnum);
            if (oftle.writeBuffer != null || data[oftle.writeBnum] != EOF_CHAR) {
                data[oftle.writeBnum] = EOF_CHAR; // 追加文件结束符
                disk.writeBlock(oftle.writeDnum, data);
                oftle.writeBuffer = null;
            }

            Lookup l = lookup(path);
            int blocks = disk.countChainBlocks(oftle.number);
            if (blocks > 255) {
                blocks = 255;
            }
            l.entry.setLengthBlocks(blocks);
            writeEntry(l);
        }

        openFileTable.remove(path);
        return "文件已关闭: " + path;
    }

    /** 删除文件：delete /a.txt */
    private String delete(String arg) throws IOException {
        Lookup l = lookup(arg);
        if (!l.ok) {
            throw new IllegalArgumentException(l.error);
        }
        if (l.entry.isDirectory()) {
            throw new IllegalArgumentException(arg + " 是目录，请用 rd");
        }
        if (openFileTable.isOpen(arg)) {
            throw new IllegalArgumentException("文件已打开，不能删除");
        }
        disk.freeChain(l.entry.startBlock());
        clearEntry(l);
        return "文件已删除: " + arg;
    }

    /** 显示文件内容：type /a.txt */
    private String type(String arg) throws IOException {
        Lookup l = lookup(arg);
        if (!l.ok) {
            throw new IllegalArgumentException(l.error);
        }
        if (l.entry.isDirectory()) {
            throw new IllegalArgumentException(arg + " 是目录，请用 dir");
        }
        if (openFileTable.isOpen(arg)) {
            throw new IllegalArgumentException("文件已打开，不能显示内容");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int dnum = l.entry.startBlock();
        while (dnum != -1) {
            byte[] data = disk.readBlock(dnum);
            for (byte b : data) {
                if (b == EOF_CHAR) {
                    return "文件内容: " + new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
                bytes.write(b & 0xFF);
            }
            dnum = disk.nextBlock(dnum);
        }
        return "文件内容: " + new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 改变文件属性：change /a.txt 1 */
    private String change(String arg) throws IOException {
        String[] parts = arg.split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("用法: change <绝对路径文件名> <新属性>");
        }
        String path = parts[0];
        int attr = parseFileAttribute(parts[1], "新属性");

        Lookup l = lookup(path);
        if (!l.ok) {
            throw new IllegalArgumentException(l.error);
        }
        if (l.entry.isDirectory()) {
            throw new IllegalArgumentException("目录属性不能修改");
        }
        if (openFileTable.isOpen(path)) {
            throw new IllegalArgumentException("文件已打开，不能改变属性");
        }
        l.entry.setAttr(attr);
        writeEntry(l);
        return "文件属性已改为 " + attr + ": " + path;
    }

    /** 系统初始化 */
    private String format() throws IOException {
        disk.format();
        openFileTable.clear();
        return "磁盘已初始化（FAT 已重建，根目录已清空）";
    }

    private String help() {
        return "可用命令:\n"
                + "  md <绝对路径>                建立目录，如 md /doc\n"
                + "  dir [绝对路径]               列目录（默认根目录），如 dir 或 dir /doc\n"
                + "  rd <绝对路径>                删除空目录，如 rd /doc\n"
                + "  create <绝对路径文件名> <属性> 建立文件并打开（写方式），如 create /a.tx 4\n"
                + "  open <绝对路径文件名> <0|1>   打开文件（0 读，1 写），如 open /a.tx 1\n"
                + "  read <绝对路径文件名> <字节数> 读文件，如 read /a.tx 20\n"
                + "  write <绝对路径文件名> <字节数> <内容>  写入内容的前 N 字节，如 write /a.tx 2 abc\n"
                + "  close <绝对路径文件名>        关闭文件，如 close /a.tx\n"
                + "  delete <绝对路径文件名>       删除文件，如 delete /a.tx\n"
                + "  type <绝对路径文件名>         显示文件内容，如 type /a.tx\n"
                + "  change <绝对路径文件名> <属性> 改变文件属性，如 change /a.tx 1\n"
                + "  format                      重新初始化磁盘\n"
                + "属性位: 1=只读 2=系统 4=普通文件 8=目录（文件可用 1/2/4 组合，如 3=只读系统文件，4=普通文件）\n"
                + "注意: 文件名为 名字.类型（名字≤3 字符，类型≤2 字符），目录名≤3 字符；只支持绝对路径（以 / 开头）。\n"
                + "正文采用 UTF-8 编码，长度按字节计算；read 截断的多字节字符会在后续读取完整后显示。\n"
                + "write（别名 write_file）必须指定字节数，范围为 0 到正文字节数，不能截断 UTF-8 字符；写 0 字节可省略内容。\n"
                + "命名规则: 文件主名和目录名区分大小写，文件后缀统一按小写处理。";
    }

    // ------------------------------------------------------------------
    // 界面只读查询接口（不改变文件系统状态）
    // ------------------------------------------------------------------

    /** 目录项只读视图，供界面展示 */
    public static final class EntryInfo {
        public final String fullName;
        public final boolean directory;
        public final int attr;
        public final int startBlock;
        public final int lengthBlocks;

        EntryInfo(DirEntry e) {
            this.fullName = e.fullName();
            this.directory = e.isDirectory();
            this.attr = e.attr();
            this.startBlock = e.startBlock();
            this.lengthBlocks = e.lengthBlocks();
        }

        public String attrText() {
            return describeAttr(attr);
        }
    }

    /** 把属性字节翻译成中文描述 */
    public static String describeAttr(int attr) {
        if ((attr & DirEntry.ATTR_DIR) != 0) {
            return "目录";
        }
        StringBuilder sb = new StringBuilder();
        if ((attr & DirEntry.ATTR_READONLY) != 0) {
            sb.append("只读 ");
        }
        if ((attr & DirEntry.ATTR_SYSTEM) != 0) {
            sb.append("系统 ");
        }
        if ((attr & DirEntry.ATTR_NORMAL) != 0) {
            sb.append("普通 ");
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? ("属性 " + attr) : s;
    }

    /** 磁盘使用统计 */
    public static final class DiskStats {
        public final int total;
        public final int system;
        public final int used;
        public final int free;
        public final int bad;

        DiskStats(int total, int system, int used, int free, int bad) {
            this.total = total;
            this.system = system;
            this.used = used;
            this.free = free;
            this.bad = bad;
        }
    }

    /** 列出目录内容；路径不存在或不是目录时抛 IllegalArgumentException */
    public List<EntryInfo> listDirectory(String path) throws IOException {
        int block;
        if (path == null || path.trim().isEmpty() || "/".equals(path.trim())) {
            block = Disk.ROOT_BLOCK;
        } else {
            String[] segs = splitPath(path.trim());
            block = traverse(segs, segs.length);
        }
        List<EntryInfo> out = new ArrayList<>();
        byte[] data = disk.readBlock(block);
        for (int i = 0; i < Disk.BLOCK_SIZE; i += DirEntry.SIZE) {
            DirEntry e = new DirEntry(data, i);
            if (!e.isEmpty()) {
                out.add(new EntryInfo(e));
            }
        }
        return out;
    }

    /** 取 FAT 表快照（128 项），供磁盘占用图绘制 */
    public int[] fatSnapshot() {
        int[] copy = new int[Disk.BLOCK_COUNT];
        for (int i = 0; i < Disk.BLOCK_COUNT; i++) {
            copy[i] = disk.getFat(i);
        }
        return copy;
    }

    /** 已打开文件表快照（最多 5 项） */
    public List<OFTLE> openFiles() {
        return new ArrayList<>(Arrays.asList(openFileTable.snapshot()));
    }

    public boolean isOpenFile(String path) {
        return openFileTable.isOpen(path);
    }

    /** 统计已用 / 空闲 / 坏块 / 系统区盘块数 */
    public DiskStats stats() {
        int system = 0;
        int used = 0;
        int free = 0;
        int bad = 0;
        for (int i = 0; i < Disk.BLOCK_COUNT; i++) {
            int v = disk.getFat(i);
            if (i < Disk.FIRST_DATA_BLOCK) {
                system++;
            } else if (v == Disk.FREE) {
                free++;
            } else if (v == Disk.BAD) {
                bad++;
            } else {
                used++;
            }
        }
        return new DiskStats(Disk.BLOCK_COUNT, system, used, free, bad);
    }

    // ------------------------------------------------------------------
    // 路径与目录项工具
    // ------------------------------------------------------------------

    private static class Lookup {
        boolean ok;
        String error;
        int parentBlock;
        int index;
        DirEntry entry;
    }

    private static class EofPos {
        int dnum;
        int bnum;
        int bytes;

        EofPos(int dnum, int bnum, int bytes) {
            this.dnum = dnum;
            this.bnum = bnum;
            this.bytes = bytes;
        }
    }

    /** 拆分绝对路径，校验合法性；"/" 返回空数组 */
    private String[] splitPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("缺少路径");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("必须使用绝对路径（以 / 开头）: " + path);
        }
        String rest = path.substring(1);
        if (rest.isEmpty()) {
            return new String[0];
        }
        String[] segs = rest.split("/", -1);
        for (String s : segs) {
            if (s.isEmpty()) {
                throw new IllegalArgumentException("路径中存在空段: " + path);
            }
        }
        return segs;
    }

    /** 从根目录出发，依次按目录名走 count 段，返回最终目录所在盘块 */
    private int traverse(String[] segs, int count) throws IOException {
        int cur = Disk.ROOT_BLOCK;
        for (int i = 0; i < count; i++) {
            int idx = findInBlock(cur, segs[i]);
            if (idx < 0) {
                throw new IllegalArgumentException("目录不存在: " + segs[i]);
            }
            DirEntry e = new DirEntry(disk.readBlock(cur), idx * DirEntry.SIZE);
            if (!e.isDirectory()) {
                throw new IllegalArgumentException(segs[i] + " 不是目录");
            }
            cur = e.startBlock();
        }
        return cur;
    }

    /** 在目录盘块中按完整名查找登记项，返回下标，未找到返回 -1 */
    private int findInBlock(int block, String name) throws IOException {
        String normalizedName = FileNames.normalizeExtension(name);
        byte[] data = disk.readBlock(block);
        for (int i = 0; i < Disk.BLOCK_SIZE; i += DirEntry.SIZE) {
            DirEntry e = new DirEntry(data, i);
            if (!e.isEmpty() && e.fullName().equals(normalizedName)) {
                return i / DirEntry.SIZE;
            }
        }
        return -1;
    }

    private int findEmptyIndex(int block) throws IOException {
        byte[] data = disk.readBlock(block);
        for (int i = 0; i < Disk.BLOCK_SIZE; i += DirEntry.SIZE) {
            DirEntry e = new DirEntry(data, i);
            if (e.isEmpty()) {
                return i / DirEntry.SIZE;
            }
        }
        return -1;
    }

    /** 查找一个文件或目录，返回其父目录块、下标与登记项 */
    private Lookup lookup(String path) throws IOException {
        String[] segs = splitPath(path);
        if (segs.length == 0) {
            Lookup l = new Lookup();
            l.ok = false;
            l.error = "路径缺少文件或目录名";
            return l;
        }
        int cur = traverse(segs, segs.length - 1);
        String last = segs[segs.length - 1];
        int idx = findInBlock(cur, last);
        if (idx < 0) {
            Lookup l = new Lookup();
            l.ok = false;
            l.error = "未找到: " + last;
            return l;
        }
        DirEntry entry = new DirEntry(disk.readBlock(cur), idx * DirEntry.SIZE);
        Lookup l = new Lookup();
        l.ok = true;
        l.parentBlock = cur;
        l.index = idx;
        l.entry = entry;
        return l;
    }

    private void putEntry(int parentBlock, int idx, DirEntry entry) throws IOException {
        byte[] data = disk.readBlock(parentBlock);
        System.arraycopy(entry.toBytes(), 0, data, idx * DirEntry.SIZE, DirEntry.SIZE);
        disk.writeBlock(parentBlock, data);
    }

    private void clearEntry(Lookup l) throws IOException {
        putEntry(l.parentBlock, l.index, DirEntry.empty());
    }

    private void writeEntry(Lookup l) throws IOException {
        putEntry(l.parentBlock, l.index, l.entry);
    }

    /** 沿 FAT 链找文件结束符 '#'，返回其位置（盘块号、块内字节号、字节长度） */
    private EofPos findEof(int startBlock) throws IOException {
        int dnum = startBlock;
        int bytes = 0;
        while (dnum != -1) {
            byte[] data = disk.readBlock(dnum);
            for (int i = 0; i < Disk.BLOCK_SIZE; i++) {
                if (data[i] == EOF_CHAR) {
                    return new EofPos(dnum, i, bytes);
                }
                bytes++;
            }
            int next = disk.nextBlock(dnum);
            if (next == -1) {
                return new EofPos(dnum, Disk.BLOCK_SIZE, bytes);
            }
            dnum = next;
        }
        return new EofPos(startBlock, 0, 0);
    }

    // ------------------------------------------------------------------
    // 名称与参数校验
    // ------------------------------------------------------------------

    private String[] parseFileName(String s, boolean needExt) {
        if (needExt) {
            int dot = s.indexOf('.');
            if (dot <= 0 || dot != s.lastIndexOf('.')) {
                throw new IllegalArgumentException("文件名格式应为 名字.类型（如 a.txt）");
            }
            String name = s.substring(0, dot);
            String ext = s.substring(dot + 1);
            if (name.length() < 1 || name.length() > 3) {
                throw new IllegalArgumentException("文件名（'.' 之前）1~3 个字符");
            }
            if (ext.length() < 1 || ext.length() > 2) {
                throw new IllegalArgumentException("类型名（'.' 之后）1~2 个字符");
            }
            checkNameChars(name);
            checkNameChars(ext);
            return new String[]{name, ext};
        } else {
            String name = s;
            if (name.length() < 1 || name.length() > 3) {
                throw new IllegalArgumentException("目录名 1~3 个字符");
            }
            checkNameChars(name);
            return new String[]{name};
        }
    }

    private void parseDirName(String s) {
        parseFileName(s, false);
    }

    private void checkNameChars(String s) {
        if (s.isEmpty()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        for (char c : s.toCharArray()) {
            if (c == '$' || c == '.' || c == '/') {
                throw new IllegalArgumentException("名称不能包含 $ . / 字符");
            }
            if (Character.isWhitespace(c) || c < 32 || c > 126) {
                throw new IllegalArgumentException("名称只能使用字母、数字及除 $ . / 以外的字符");
            }
        }
    }

    private int parseFileAttribute(String s, String what) {
        int attr = parseByte(s, what);
        int allowed = DirEntry.ATTR_READONLY | DirEntry.ATTR_SYSTEM | DirEntry.ATTR_NORMAL;
        if ((attr & ~allowed) != 0) {
            throw new IllegalArgumentException("文件属性只能包含只读(1)、系统(2)、普通文件(4)及其组合，不能包含目录位或保留位");
        }
        return attr;
    }

    private int parseByte(String s, String what) {
        try {
            int v = Integer.parseInt(s);
            if (v < 0 || v > 255) {
                throw new IllegalArgumentException(what + " 必须在 0~255 之间");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " 必须是 0~255 的整数");
        }
    }

    private int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " 必须是整数");
        }
    }
}
