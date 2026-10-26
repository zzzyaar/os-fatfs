package fatfs;

/**
 * 已打开文件表：用数组模拟，系统中允许打开文件的最大数量 n = 5。
 * 主要操作：查找、插入、删除。
 */
public class OpenFileTable {
    public static final int MAX = 5;

    private final OFTLE[] items = new OFTLE[MAX];
    private int count = 0;

    public int find(String name) {
        for (int i = 0; i < count; i++) {
            if (items[i].name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    public boolean isOpen(String name) {
        return find(name) >= 0;
    }

    public OFTLE get(String name) {
        int i = find(name);
        return i < 0 ? null : items[i];
    }

    public boolean add(OFTLE entry) {
        if (count >= MAX) {
            return false;
        }
        items[count++] = entry;
        return true;
    }

    public boolean remove(String name) {
        int i = find(name);
        if (i < 0) {
            return false;
        }
        for (int j = i; j < count - 1; j++) {
            items[j] = items[j + 1];
        }
        items[--count] = null;
        return true;
    }

    public void clear() {
        for (int i = 0; i < count; i++) {
            items[i] = null;
        }
        count = 0;
    }

    public int size() {
        return count;
    }

    /** 已打开文件表快照（仅前 count 项） */
    public OFTLE[] snapshot() {
        return java.util.Arrays.copyOf(items, count);
    }

    public String listOpen() {
        if (count == 0) {
            return "  （空）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            OFTLE e = items[i];
            sb.append(String.format("  %s  属性=%d  起始块=%d  长度=%d字节  方式=%s%n",
                    e.name, e.attribute, e.number, e.length, e.mode()));
        }
        return sb.toString();
    }
}
