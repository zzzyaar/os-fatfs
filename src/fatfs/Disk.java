package fatfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * 模拟磁盘：用文件模拟磁盘，128 个物理块，每块 64 字节。
 * <pre>
 * FAT 放在磁盘开始处：128 项，每项 1 字节，占第 0、1 块。
 *   0        = 空闲盘块
 *   255(EOF) = 文件结束
 *   254(BAD) = 坏盘块（预设第 23、49 块损坏）
 *   其它值    = 文件下一块的块号
 * 根目录固定在第 2 块，占 1 块（8 个目录项）。
 * 系统区域为 0~2 块，分配从第 3 块开始。
 * </pre>
 * 按题目要求用两个数组 buffer1、buffer2 模拟缓冲区：读入一块到 buffer1，
 * 写盘时先放入 buffer2，再整体写入磁盘文件，不把整个磁盘读入内存。
 */
public class Disk {
    public static final int BLOCK_COUNT = 128;
    public static final int BLOCK_SIZE = 64;
    public static final int FAT_BLOCKS = 2;
    public static final int ROOT_BLOCK = 2;
    public static final int FIRST_DATA_BLOCK = 3;

    public static final int FREE = 0;
    public static final int BAD = 254;
    public static final int EOF = 255;

    public static final int[] BAD_BLOCKS = {23, 49};

    private final RandomAccessFile file;
    private final byte[] fat = new byte[BLOCK_COUNT];
    private final byte[] buffer1 = new byte[BLOCK_SIZE]; // 读缓冲
    private final byte[] buffer2 = new byte[BLOCK_SIZE]; // 写缓冲

    public Disk(String diskPath) throws IOException {
        file = new RandomAccessFile(diskPath, "rw");
        format(); // 题目要求：首先系统初始化
    }

    /** 系统初始化：建立模拟磁盘文件、初始化 FAT、根目录初始化为空目录项 */
    public void format() throws IOException {
        file.setLength(0);
        file.setLength((long) BLOCK_COUNT * BLOCK_SIZE);

        Arrays.fill(fat, (byte) FREE);
        fat[0] = (byte) EOF;          // 系统区：FAT 占第 0 块
        fat[1] = (byte) EOF;          // 系统区：FAT 占第 1 块
        fat[ROOT_BLOCK] = (byte) EOF; // 系统区：根目录占第 2 块
        for (int b : BAD_BLOCKS) {
            fat[b] = (byte) BAD;      // 预设坏块
        }
        writeFat();

        writeBlock(ROOT_BLOCK, emptyDirBlock());
    }

    /** 一个空目录盘块：64 字节，8 个空目录项 */
    public static byte[] emptyDirBlock() {
        byte[] data = new byte[BLOCK_SIZE];
        Arrays.fill(data, (byte) ' ');
        for (int i = 0; i < BLOCK_SIZE; i += DirEntry.SIZE) {
            data[i] = '$';
        }
        return data;
    }

    /** 读一个盘块（经过 buffer1） */
    public byte[] readBlock(int blockNo) throws IOException {
        checkBlockNo(blockNo);
        file.seek((long) blockNo * BLOCK_SIZE);
        file.readFully(buffer1);
        return buffer1.clone();
    }

    /** 写一个盘块（经过 buffer2） */
    public void writeBlock(int blockNo, byte[] data) throws IOException {
        checkBlockNo(blockNo);
        if (data.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("盘块数据长度必须为 " + BLOCK_SIZE + " 字节");
        }
        System.arraycopy(data, 0, buffer2, 0, BLOCK_SIZE);
        file.seek((long) blockNo * BLOCK_SIZE);
        file.write(buffer2);
    }

    public int getFat(int blockNo) {
        return fat[blockNo] & 0xFF;
    }

    public void setFat(int blockNo, int value) throws IOException {
        fat[blockNo] = (byte) value;
        writeFat();
    }

    /** 取某块的下一块块号；EOF/坏块/空闲均返回 -1 */
    public int nextBlock(int blockNo) {
        int v = fat[blockNo] & 0xFF;
        if (v == EOF || v == BAD || v == FREE) {
            return -1;
        }
        return v;
    }

    /** 分配一个空闲盘块（从系统区之后开始查），返回块号，无空闲返回 -1 */
    public int allocBlock() throws IOException {
        for (int i = FIRST_DATA_BLOCK; i < BLOCK_COUNT; i++) {
            if ((fat[i] & 0xFF) == FREE) {
                fat[i] = (byte) EOF;
                writeFat();
                return i;
            }
        }
        return -1;
    }

    /** 回收一个盘块 */
    public void freeBlock(int blockNo) throws IOException {
        fat[blockNo] = (byte) FREE;
        writeFat();
    }

    /** 沿 FAT 链回收从 startBlock 开始的全部盘块 */
    public void freeChain(int startBlock) throws IOException {
        int b = startBlock;
        while (b >= FIRST_DATA_BLOCK && b < BLOCK_COUNT) {
            int next = fat[b] & 0xFF;
            fat[b] = (byte) FREE;
            if (next == EOF || next == FREE || next == BAD) {
                break;
            }
            b = next;
        }
        writeFat();
    }

    /** 统计从 startBlock 开始的 FAT 链块数 */
    public int countChainBlocks(int startBlock) {
        int count = 0;
        int b = startBlock;
        while (b >= FIRST_DATA_BLOCK && b < BLOCK_COUNT && count < BLOCK_COUNT) {
            count++;
            b = nextBlock(b);
        }
        return count;
    }

    public void close() throws IOException {
        file.close();
    }

    private void writeFat() throws IOException {
        writeBlock(0, Arrays.copyOfRange(fat, 0, BLOCK_SIZE));
        writeBlock(1, Arrays.copyOfRange(fat, BLOCK_SIZE, 2 * BLOCK_SIZE));
    }

    private void checkBlockNo(int blockNo) {
        if (blockNo < 0 || blockNo >= BLOCK_COUNT) {
            throw new IllegalArgumentException("盘块号越界: " + blockNo);
        }
    }
}
