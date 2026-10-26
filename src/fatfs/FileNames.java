package fatfs;

import java.util.Locale;

/** 主名和目录名保留大小写，仅将最后一段的文件后缀统一为小写。 */
final class FileNames {
    private FileNames() {
    }

    static String normalizeExtension(String path) {
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        if (dot <= path.lastIndexOf('/')) {
            return path;
        }
        return path.substring(0, dot + 1) + path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
