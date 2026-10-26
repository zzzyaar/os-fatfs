package fatfs;

/**
 * 已打开文件表项（OFTLE），对应题目中的 C 结构：
 * <pre>
 * name[20]   文件绝对路径名
 * attribute  文件属性（1 字节）
 * number     文件起始盘块号
 * length     文件长度（字节数）
 * flag       操作类型：0 读方式，1 写方式
 * read       读指针 {dnum 盘块号, bnum 块内字节号}
 * write      写指针 {dnum 盘块号, bnum 块内字节号}
 * </pre>
 */
public class OFTLE {
    public static final int READ = 0;
    public static final int WRITE = 1;

    public String name;        // 文件绝对路径名
    public int attribute;      // 文件属性
    public int number;         // 文件起始盘块号
    public int length;         // 文件长度（字节数）
    public int flag;           // 操作类型：0 读，1 写
    public int readDnum;       // 读指针：盘块号
    public int readBnum;       // 读指针：块内字节号
    public int writeDnum;      // 写指针：盘块号
    public int writeBnum;      // 写指针：块内字节号

    public OFTLE(String name, int attribute, int number, int length, int flag,
                 int readDnum, int readBnum, int writeDnum, int writeBnum) {
        this.name = name;
        this.attribute = attribute;
        this.number = number;
        this.length = length;
        this.flag = flag;
        this.readDnum = readDnum;
        this.readBnum = readBnum;
        this.writeDnum = writeDnum;
        this.writeBnum = writeBnum;
    }

    public String mode() {
        return flag == READ ? "读" : "写";
    }
}
