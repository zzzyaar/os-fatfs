package fatfs;

import java.util.Arrays;

/**
 * 8 字节目录登记项（题目五：目录项结构）。
 * <pre>
 * 文件名  3 字节（首字节 '$' 表示空目录项）
 * 类型名  2 字节（目录登记项此处填空格）
 * 属性    1 字节（bit0 只读，bit1 系统，bit2 普通文件，bit3 目录）
 * 起始盘块号 1 字节
 * 文件长度 1 字节（单位为盘块；目录登记项此处填 0）
 * </pre>
 */
public class DirEntry {
    public static final int SIZE = 8;

    public static final int ATTR_READONLY = 1;   // 0000 0001
    public static final int ATTR_SYSTEM = 2;     // 0000 0010
    public static final int ATTR_NORMAL = 4;     // 0000 0100 可读可写普通文件
    public static final int ATTR_DIR = 8;        // 0000 1000 目录登记项

    private final byte[] raw = new byte[SIZE];

    /** 从盘块数据中 offset 处读出一个目录项 */
    public DirEntry(byte[] block, int offset) {
        System.arraycopy(block, offset, raw, 0, SIZE);
    }

    private DirEntry() {
    }

    /** 空目录项：全部空格，首字节 '$' */
    public static DirEntry empty() {
        DirEntry e = new DirEntry();
        Arrays.fill(e.raw, (byte) ' ');
        e.raw[0] = '$';
        return e;
    }

    /** 文件目录项 */
    public static DirEntry file(String name, String ext, int attr, int startBlock, int lengthBlocks) {
        DirEntry e = empty();
        putName(e.raw, name, 0, 3);
        putName(e.raw, ext, 3, 2);
        e.raw[5] = (byte) attr;
        e.raw[6] = (byte) startBlock;
        e.raw[7] = (byte) lengthBlocks;
        return e;
    }

    /** 目录登记项 */
    public static DirEntry dir(String name, int startBlock) {
        DirEntry e = empty();
        putName(e.raw, name, 0, 3);
        e.raw[3] = ' ';
        e.raw[4] = ' ';
        e.raw[5] = ATTR_DIR;
        e.raw[6] = (byte) startBlock;
        e.raw[7] = 0;
        return e;
    }

    private static void putName(byte[] raw, String s, int offset, int len) {
        for (int i = 0; i < len; i++) {
            raw[offset + i] = i < s.length() ? (byte) s.charAt(i) : (byte) ' ';
        }
    }

    public String name() {
        return new String(raw, 0, 3, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    public String ext() {
        return new String(raw, 3, 2, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    public int attr() {
        return raw[5] & 0xFF;
    }

    public int startBlock() {
        return raw[6] & 0xFF;
    }

    public int lengthBlocks() {
        return raw[7] & 0xFF;
    }

    public void setAttr(int attr) {
        raw[5] = (byte) attr;
    }

    public void setLengthBlocks(int lengthBlocks) {
        raw[7] = (byte) lengthBlocks;
    }

    public boolean isEmpty() {
        return raw[0] == '$';
    }

    public boolean isDirectory() {
        return (attr() & ATTR_DIR) != 0;
    }

    /** 目录名或 文件名.类型名 */
    public String fullName() {
        return isDirectory() ? name() : name() + "." + ext();
    }

    public byte[] toBytes() {
        return raw.clone();
    }

    public String describe() {
        if (isDirectory()) {
            return String.format("%-6s <DIR> 属性=%d 起始块=%d", name(), attr(), startBlock());
        }
        return String.format("%-6s 属性=%d 起始块=%d 长度=%d块", fullName(), attr(), startBlock(), lengthBlocks());
    }
}
