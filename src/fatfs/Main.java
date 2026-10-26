package fatfs;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 图形界面入口：模拟磁盘文件系统（题目五）。
 * <pre>
 * 顶部   ：标题 + 规格说明 + 磁盘/打开文件数徽章
 * 左侧上 ：目录树（支持双击进入、右键快捷菜单）
 * 左侧下 ：磁盘盘块占用图 / 已打开文件表
 * 右侧   ：命令行控制台（彩色输出 + 输入框 + 历史）
 * 底部   ：分组命令面板 + 状态栏
 * </pre>
 */
public class Main {
    private static final Theme.BlockIcon ICON_DIR = new Theme.BlockIcon(Theme.BLK_DIR, 10);
    private static final Theme.BlockIcon ICON_FILE = new Theme.BlockIcon(Theme.BLK_USED, 10);

    private FileSystem fs;

    private JFrame frame;
    private JTextPane terminal;
    private Theme.HintField input;
    private JTree tree;
    private DiskMapPanel diskMap;
    private DefaultTableModel openFileModel;
    private Theme.Pill pillDisk;
    private Theme.Pill pillOpen;
    private JLabel statusLeft;
    private JLabel statusRight;

    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;
    private String currentDir = "/";
    private boolean firstBuild = true;

    private SimpleAttributeSet styleCmd;
    private SimpleAttributeSet styleOut;
    private SimpleAttributeSet styleErr;
    private SimpleAttributeSet styleMsg;
    private SimpleAttributeSet styleOk;

    public static void main(String[] args) {
        Theme.install();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new Main().start();
            }
        });
    }

    private Main() {
        try {
            fs = new FileSystem();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "磁盘初始化失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private void start() {
        if (fs == null) {
            return;
        }
        buildStyles();
        buildUi();
        refreshAll();
        welcome();
        frame.setVisible(true);
        input.requestFocusInWindow();
    }

    // ------------------------------------------------------------------
    // 界面搭建
    // ------------------------------------------------------------------

    private void buildUi() {
        frame = new JFrame("模拟磁盘文件系统 · 题目五");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.setBorder(new EmptyBorder(14, 16, 10, 16));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(buildCommandBar(), BorderLayout.CENTER);
        south.add(buildStatusBar(), BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setSize(1280, 840);
        frame.setMinimumSize(new Dimension(1040, 680));
        frame.setLocationRelativeTo(null);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 4, 12, 4));

        JPanel titles = new JPanel(new BorderLayout(0, 3));
        titles.setOpaque(false);
        JLabel title = new JLabel("模拟磁盘文件系统");
        title.setFont(Theme.ui(Font.BOLD, 20));
        title.setForeground(Theme.TEXT);
        JLabel sub = new JLabel("FAT 显式链接 · 128 块 × 64 字节 · 根目录固定第 2 块 · "
                + "坏块 23 / 49 · 文件结束符 #");
        sub.setFont(Theme.ui(Font.PLAIN, 12));
        sub.setForeground(Theme.MUTED);
        titles.add(title, BorderLayout.NORTH);
        titles.add(sub, BorderLayout.CENTER);
        header.add(titles, BorderLayout.WEST);

        JPanel pills = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        pills.setOpaque(false);
        pillDisk = new Theme.Pill(" ", Theme.PRIMARY_DARK, Theme.PRIMARY_BG);
        pillOpen = new Theme.Pill(" ", Theme.SUCCESS, new Color(0xE6F6EC));
        pills.add(pillDisk);
        pills.add(pillOpen);
        header.add(pills, BorderLayout.EAST);
        return header;
    }

    private JComponent buildBody() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), buildRight());
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(10);
        split.setResizeWeight(0.38);
        split.setContinuousLayout(true);
        return split;
    }

    private JComponent buildLeft() {
        JSplitPane v = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildTreeCard(), buildStateCard());
        v.setBorder(null);
        v.setOpaque(false);
        v.setDividerSize(10);
        v.setResizeWeight(0.52);
        v.setContinuousLayout(true);
        v.setPreferredSize(new Dimension(438, 600));
        return v;
    }

    private JComponent buildTreeCard() {
        Theme.Card card = new Theme.Card("目录树");
        Theme.RoundedButton refresh = Theme.softButton("刷新");
        refresh.addActionListener(e -> refreshAll());
        card.setHeaderRight(refresh);

        tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode(
                new NodeData("/", "根目录 /", true))));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setFont(Theme.ui(Font.PLAIN, 13));
        tree.setBackground(Theme.CARD);
        tree.setBorder(new EmptyBorder(2, 2, 2, 2));
        tree.setCellRenderer(new NodeRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(this::onTreeSelection);
        tree.addMouseListener(new TreeMouseHandler());

        JScrollPane scroll = Theme.scroll(tree);
        scroll.getViewport().setBackground(Theme.CARD);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildStateCard() {
        Theme.Card card = new Theme.Card("磁盘状态");

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.ui(Font.PLAIN, 12));
        tabs.setBackground(Theme.CARD);
        tabs.setForeground(Theme.TEXT);
        tabs.setBorder(new EmptyBorder(2, 0, 0, 0));

        diskMap = new DiskMapPanel();
        JPanel mapPane = new JPanel(new BorderLayout(0, 4));
        mapPane.setOpaque(false);
        mapPane.add(diskMap, BorderLayout.CENTER);
        mapPane.add(DiskMapPanel.legend(), BorderLayout.SOUTH);
        tabs.addTab("盘块占用图", mapPane);
        tabs.addTab("已打开文件表", buildOpenFileTable());

        card.add(tabs, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildOpenFileTable() {
        openFileModel = new DefaultTableModel(
                new Object[]{"文件路径名", "方式", "属性", "起始块", "长度/字节", "读指针 / 写指针"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(openFileModel);
        table.setFont(Theme.ui(Font.PLAIN, 12));
        table.setRowHeight(24);
        table.setShowVerticalLines(false);
        table.setGridColor(new Color(0xEEF2F7));
        table.setSelectionBackground(Theme.PRIMARY_BG);
        table.setSelectionForeground(Theme.PRIMARY_DARK);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        int[] widths = {150, 46, 76, 56, 68, 120};
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JTableHeader header = table.getTableHeader();
        header.setFont(Theme.ui(Font.BOLD, 12));
        header.setBackground(new Color(0xF6F8FC));
        header.setForeground(Theme.MUTED);
        header.setPreferredSize(new Dimension(10, 28));
        if (header.getDefaultRenderer() instanceof DefaultTableCellRenderer) {
            ((DefaultTableCellRenderer) header.getDefaultRenderer())
                    .setHorizontalAlignment(SwingConstants.LEFT);
        }

        JScrollPane scroll = Theme.scroll(table);
        scroll.getViewport().setBackground(Theme.CARD);
        return scroll;
    }

    private JComponent buildRight() {
        Theme.Card card = new Theme.Card("命令行控制台");
        card.setDark();

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        Theme.RoundedButton help = darkButton("命令帮助");
        help.addActionListener(e -> exec("help"));
        Theme.RoundedButton clear = darkButton("清屏");
        clear.addActionListener(e -> terminal.setText(""));
        actions.add(help);
        actions.add(clear);
        card.setHeaderRight(actions);

        terminal = new JTextPane();
        terminal.setEditable(false);
        terminal.setBackground(Theme.TERM_BG);
        terminal.setForeground(Theme.TERM_FG);
        terminal.setFont(Theme.mono(Font.PLAIN, 13));
        terminal.setCaretColor(Theme.TERM_FG);
        terminal.setBorder(new EmptyBorder(6, 8, 6, 8));

        card.add(Theme.scroll(terminal), BorderLayout.CENTER);
        card.add(buildInputBar(), BorderLayout.SOUTH);
        return card;
    }

    private Theme.RoundedButton darkButton(String text) {
        return new Theme.RoundedButton(text, new Color(0x1E293B), new Color(0x334155),
                new Color(0x0B1220), Theme.TERM_FG, 8);
    }

    private JComponent buildInputBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(10, 0, 0, 0));

        JLabel prompt = new JLabel(">");
        prompt.setFont(Theme.mono(Font.BOLD, 16));
        prompt.setForeground(Theme.TERM_CMD);

        input = new Theme.HintField();
        input.setFont(Theme.mono(Font.PLAIN, 14));
        input.setBackground(Theme.TERM_INPUT_BG);
        input.setForeground(Theme.TERM_FG);
        input.setCaretColor(Theme.TERM_CMD);
        input.setBorder(BorderFactory.createCompoundBorder(
                new Theme.RoundBorder(Theme.TERM_INPUT_BORDER, 12, 10, 7),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)));
        input.setHint("输入命令后回车执行，例如：dir /doc　（↑ ↓ 翻历史命令）", Theme.TERM_HINT);
        input.addActionListener(e -> onRun());
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    if (!history.isEmpty() && historyIndex > 0) {
                        historyIndex--;
                        input.setText(history.get(historyIndex));
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    if (historyIndex < history.size() - 1) {
                        historyIndex++;
                        input.setText(history.get(historyIndex));
                    } else {
                        historyIndex = history.size();
                        input.setText("");
                    }
                    e.consume();
                }
            }
        });

        Theme.RoundedButton run = Theme.primaryButton("执行");
        run.addActionListener(e -> onRun());

        bar.add(prompt, BorderLayout.WEST);
        bar.add(input, BorderLayout.CENTER);
        bar.add(run, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildCommandBar() {
        Theme.Card card = new Theme.Card("命令面板");
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);

        row.add(groupLabel("目录"));
        row.add(cmdButton("建立目录", "md"));
        row.add(cmdButton("列目录", "dir"));
        row.add(cmdButton("删空目录", "rd"));
        row.add(groupLabel("文件"));
        row.add(cmdButton("建立文件", "create"));
        row.add(cmdButton("打开", "open"));
        row.add(cmdButton("读", "read"));
        row.add(cmdButton("写", "write"));
        row.add(cmdButton("关闭", "close"));
        row.add(cmdButton("显示内容", "type"));
        row.add(cmdButton("删除", "delete"));
        row.add(groupLabel("属性 / 系统"));
        row.add(cmdButton("改属性", "change"));
        row.add(cmdButton("初始化磁盘", "format"));
        row.add(cmdButton("帮助", "help"));
        row.add(cmdButton("清屏", "clear"));

        card.add(row, BorderLayout.CENTER);
        return card;
    }

    private JLabel groupLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.ui(Font.BOLD, 12));
        l.setForeground(Theme.MUTED);
        l.setBorder(new EmptyBorder(0, 8, 0, 2));
        return l;
    }

    private Theme.RoundedButton cmdButton(String text, String cmd) {
        Theme.RoundedButton b = Theme.softButton(text);
        b.setToolTipText(tooltipOf(cmd));
        b.addActionListener(e -> onCommand(cmd));
        return b;
    }

    private static String tooltipOf(String cmd) {
        switch (cmd) {
            case "md":     return "md <绝对路径>　建立目录，如 md /doc";
            case "dir":    return "dir [绝对路径]　列出目录内容";
            case "rd":     return "rd <绝对路径>　删除空目录";
            case "create": return "create <路径文件名> <属性>　建立文件并打开（写方式）";
            case "open":   return "open <路径文件名> <0读|1写>　打开文件";
            case "read":   return "read <路径文件名> <长度>　读文件";
            case "write":  return "write <路径文件名> <内容>　写文件（末尾追加）";
            case "close":  return "close <路径文件名>　关闭文件";
            case "delete": return "delete <路径文件名>　删除文件";
            case "type":   return "type <路径文件名>　显示文件内容（文件未打开时）";
            case "change": return "change <路径文件名> <属性>　改变文件属性";
            case "format": return "重新初始化模拟磁盘（数据全部丢失）";
            case "help":   return "显示全部命令说明";
            default:       return "清空控制台输出";
        }
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(6, 8, 0, 8));

        statusLeft = new JLabel(" ");
        statusLeft.setFont(Theme.ui(Font.PLAIN, 11));
        statusLeft.setForeground(Theme.MUTED);

        statusRight = new JLabel(" ");
        statusRight.setFont(Theme.ui(Font.PLAIN, 11));
        statusRight.setForeground(Theme.FAINT);

        bar.add(statusLeft, BorderLayout.WEST);
        bar.add(statusRight, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------
    // 命令执行
    // ------------------------------------------------------------------

    private void onRun() {
        String line = input.getText();
        input.setText("");
        exec(line);
    }

    private void exec(String cmdLine) {
        String line = cmdLine == null ? "" : cmdLine.trim();
        if (line.isEmpty()) {
            return;
        }
        if (history.isEmpty() || !history.get(history.size() - 1).equals(line)) {
            history.add(line);
        }
        historyIndex = history.size();

        append("> " + line + "\n", styleCmd);
        FileSystem.Result r = fs.run(line);
        if (!r.text.isEmpty()) {
            append(r.text + "\n", r.ok ? styleOut : styleErr);
        }
        append("\n", styleOut);
        refreshAll();
    }

    /** 命令面板按钮：不需要参数的直接执行，需要参数的弹出预填模板 */
    private void onCommand(String cmd) {
        switch (cmd) {
            case "dir":
                exec("dir " + dirPath());
                break;
            case "help":
                exec("help");
                break;
            case "clear":
                if (terminal != null) {
                    terminal.setText("");
                }
                break;
            case "format":
                if (confirm("确定重新初始化模拟磁盘？所有文件将丢失。")) {
                    exec("format");
                }
                break;
            case "md":
                prompt("建立目录", "md " + join(dirPath(), "new"));
                break;
            case "rd":
                prompt("删除空目录", "rd " + dirPath());
                break;
            case "create":
                prompt("建立文件并打开（写方式）", "create " + join(dirPath(), "a.tx") + " 4");
                break;
            case "open":
                prompt("打开文件（0=读，1=写）", "open " + filePath() + " 0");
                break;
            case "read":
                prompt("读文件", "read " + filePath() + " 20");
                break;
            case "write":
                prompt("写文件（末尾追加）", "write " + filePath() + " hello");
                break;
            case "close":
                prompt("关闭文件", "close " + filePath());
                break;
            case "delete":
                prompt("删除文件", "delete " + filePath());
                break;
            case "type":
                prompt("显示文件内容", "type " + filePath());
                break;
            case "change":
                prompt("改变文件属性", "change " + filePath() + " 1");
                break;
            default:
                break;
        }
    }

    private void prompt(String title, String template) {
        Object value = JOptionPane.showInputDialog(frame, "可在下方修改命令后执行：", title,
                JOptionPane.PLAIN_MESSAGE, null, null, template);
        if (value != null) {
            String line = value.toString().trim();
            if (!line.isEmpty()) {
                exec(line);
            }
        }
    }

    private String ask(String message, String title) {
        return JOptionPane.showInputDialog(frame, message, title, JOptionPane.PLAIN_MESSAGE);
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(frame, message, "确认",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    // ------------------------------------------------------------------
    // 界面刷新
    // ------------------------------------------------------------------

    private void refreshAll() {
        Set<String> expanded = collectExpanded();
        refreshTree(expanded);
        refreshDiskMap();
        refreshOpenFiles();
        refreshStatus();
    }

    private Set<String> collectExpanded() {
        Set<String> set = new HashSet<>();
        Object rootObj = tree.getModel().getRoot();
        if (!(rootObj instanceof DefaultMutableTreeNode)) {
            return set;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) rootObj;
        TreePath rootPath = new TreePath(root.getPath());
        tree.expandPath(rootPath);
        collectExpanded(root, rootPath, set);
        return set;
    }

    private void collectExpanded(DefaultMutableTreeNode node, TreePath path, Set<String> set) {
        if (tree.isExpanded(path) && node.getUserObject() instanceof NodeData) {
            set.add(((NodeData) node.getUserObject()).path);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            collectExpanded(child, path.pathByAddingChild(child), set);
        }
    }

    private void refreshTree(Set<String> expanded) {
        DefaultMutableTreeNode root = buildTreeNodes();
        tree.setModel(new DefaultTreeModel(root));
        TreePath rootPath = new TreePath(root.getPath());
        boolean expandAll = firstBuild || expanded.isEmpty();
        restoreExpansion(root, rootPath, expanded, expandAll);
        firstBuild = false;
    }

    private DefaultMutableTreeNode buildTreeNodes() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                new NodeData("/", "根目录 /（块 " + Disk.ROOT_BLOCK + "）", true));
        Set<Integer> visited = new HashSet<>();
        visited.add(Disk.ROOT_BLOCK);
        addChildren(root, "/", visited, 0);
        return root;
    }

    private void addChildren(DefaultMutableTreeNode parent, String path,
                             Set<Integer> visited, int depth) {
        if (depth > 6) {
            return;
        }
        List<FileSystem.EntryInfo> items = safeList(path);
        for (FileSystem.EntryInfo it : items) {
            String childPath = join(path, it.fullName);
            if (it.directory) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                        new NodeData(childPath, it.fullName + "/", true));
                parent.add(node);
                if (visited.add(it.startBlock)) {
                    addChildren(node, childPath, visited, depth + 1);
                }
            } else {
                String label = it.fullName + "（" + it.lengthBlocks + " 块）";
                if (fs.isOpenFile(childPath)) {
                    label = label + " ●";
                }
                parent.add(new DefaultMutableTreeNode(new NodeData(childPath, label, false)));
            }
        }
    }

    private List<FileSystem.EntryInfo> safeList(String path) {
        try {
            return fs.listDirectory(path);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void restoreExpansion(DefaultMutableTreeNode node, TreePath path,
                                  Set<String> expanded, boolean expandAll) {
        if (node.getUserObject() instanceof NodeData) {
            String p = ((NodeData) node.getUserObject()).path;
            if (expandAll || expanded.contains(p)) {
                tree.expandPath(path);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            restoreExpansion(child, path.pathByAddingChild(child), expanded, expandAll);
        }
    }

    private void refreshDiskMap() {
        Set<Integer> dirBlocks = new HashSet<>();
        dirBlocks.add(Disk.ROOT_BLOCK);
        collectDirBlocks("/", dirBlocks, new HashSet<Integer>(), 0);
        diskMap.setData(fs.fatSnapshot(), dirBlocks);
    }

    private void collectDirBlocks(String path, Set<Integer> blocks,
                                  Set<Integer> visited, int depth) {
        if (depth > 6) {
            return;
        }
        for (FileSystem.EntryInfo it : safeList(path)) {
            if (!it.directory) {
                continue;
            }
            if (visited.add(it.startBlock)) {
                blocks.add(it.startBlock);
                collectDirBlocks(join(path, it.fullName), blocks, visited, depth + 1);
            }
        }
    }

    private void refreshOpenFiles() {
        openFileModel.setRowCount(0);
        for (OFTLE e : fs.openFiles()) {
            openFileModel.addRow(new Object[]{
                    e.name,
                    e.mode(),
                    FileSystem.describeAttr(e.attribute),
                    String.valueOf(e.number),
                    String.valueOf(e.length),
                    e.readDnum + ":" + e.readBnum + " / " + e.writeDnum + ":" + e.writeBnum
            });
        }
    }

    private void refreshStatus() {
        FileSystem.DiskStats st = fs.stats();
        int dataBlocks = st.total - st.system - st.bad;

        pillDisk.setText("磁盘已用 " + st.used + " / " + dataBlocks + " 块");
        pillOpen.setText("打开文件 " + fs.openFiles().size() + " / " + OpenFileTable.MAX);
        pillDisk.revalidate();
        pillOpen.revalidate();

        statusLeft.setText("空闲 " + st.free + " 块　坏块 " + st.bad + " 块　系统区 " + st.system + " 块");
        updateDirHint();
    }

    private void updateDirHint() {
        statusRight.setText("当前目录：" + currentDir + "　·　双击目录执行 dir，右键查看更多操作");
    }

    // ------------------------------------------------------------------
    // 目录树交互
    // ------------------------------------------------------------------

    private void onTreeSelection(TreeSelectionEvent e) {
        NodeData d = nodeData(e.getNewLeadSelectionPath());
        if (d == null) {
            return;
        }
        currentDir = d.dir ? d.path : parentOf(d.path);
        updateDirHint();
    }

    private NodeData selectedNode() {
        return nodeData(tree.getSelectionPath());
    }

    /** 命令面板模板使用的目录路径 */
    private String dirPath() {
        NodeData d = selectedNode();
        if (d == null) {
            return currentDir;
        }
        return d.dir ? d.path : parentOf(d.path);
    }

    /** 命令面板模板使用的文件路径 */
    private String filePath() {
        NodeData d = selectedNode();
        if (d != null && !d.dir) {
            return d.path;
        }
        return join(dirPath(), "a.tx");
    }

    private JPopupMenu buildPopup(NodeData d) {
        JPopupMenu menu = new JPopupMenu();
        if (d.dir) {
            menu.add(item("列出目录内容", () -> exec("dir " + d.path)));
            menu.addSeparator();
            menu.add(item("新建目录…", () -> {
                String name = ask("目录名（1~3 个字符，不能含 $ . /）：", "新建目录");
                if (name != null && !name.trim().isEmpty()) {
                    exec("md " + join(d.path, name.trim()));
                }
            }));
            menu.add(item("新建文件…", () -> {
                String name = ask("文件名（如 a.tx；名字≤3 字符、类型≤2 字符）：", "新建文件");
                if (name == null || name.trim().isEmpty()) {
                    return;
                }
                String attr = ask("文件属性（4=普通文件，2=系统文件，3=只读系统文件）：", "文件属性");
                if (attr != null && !attr.trim().isEmpty()) {
                    exec("create " + join(d.path, name.trim()) + " " + attr.trim());
                }
            }));
            menu.addSeparator();
            menu.add(item("删除空目录", () -> {
                if (confirm("确定删除空目录 " + d.path + " ？")) {
                    exec("rd " + d.path);
                }
            }));
        } else {
            menu.add(item("显示文件内容", () -> exec("type " + d.path)));
            menu.add(item("以读方式打开", () -> exec("open " + d.path + " 0")));
            menu.add(item("以写方式打开", () -> exec("open " + d.path + " 1")));
            menu.addSeparator();
            menu.add(item("读文件…", () -> {
                String n = ask("读取字节数：", "读文件");
                if (n != null && !n.trim().isEmpty()) {
                    exec("read " + d.path + " " + n.trim());
                }
            }));
            menu.add(item("写文件…", () -> {
                String text = ask("写入内容（末尾追加）：", "写文件");
                if (text != null && !text.isEmpty()) {
                    exec("write " + d.path + " " + text);
                }
            }));
            menu.add(item("关闭文件", () -> exec("close " + d.path)));
            menu.addSeparator();
            menu.add(item("改变属性…", () -> {
                String attr = ask("新属性（1=只读，2=系统，4=普通文件，可组合如 3）：", "改变属性");
                if (attr != null && !attr.trim().isEmpty()) {
                    exec("change " + d.path + " " + attr.trim());
                }
            }));
            menu.add(item("删除文件", () -> {
                if (confirm("确定删除文件 " + d.path + " ？")) {
                    exec("delete " + d.path);
                }
            }));
        }
        menu.addSeparator();
        menu.add(item("刷新视图", this::refreshAll));
        return menu;
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem mi = new JMenuItem(text);
        mi.setFont(Theme.ui(Font.PLAIN, 13));
        mi.addActionListener(e -> action.run());
        return mi;
    }

    private final class TreeMouseHandler extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            maybePopup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybePopup(e);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            NodeData d = nodeData(path);
            if (d == null) {
                return;
            }
            if (d.dir) {
                currentDir = d.path;
                exec("dir " + d.path);
                tree.expandPath(path);
            } else {
                exec("type " + d.path);
            }
        }

        private void maybePopup(MouseEvent e) {
            if (!e.isPopupTrigger()) {
                return;
            }
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            NodeData d = nodeData(path);
            if (d == null) {
                return;
            }
            tree.setSelectionPath(path);
            buildPopup(d).show(tree, e.getX(), e.getY());
        }
    }

    private final class NodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
            setBackgroundNonSelectionColor(Theme.CARD);
            setBackgroundSelectionColor(Theme.PRIMARY_BG);
            setTextNonSelectionColor(Theme.TEXT);
            setTextSelectionColor(Theme.PRIMARY_DARK);
            setBorderSelectionColor(Theme.PRIMARY_BG);
            setFont(Theme.ui(Font.PLAIN, 13));
            if (value instanceof DefaultMutableTreeNode) {
                Object o = ((DefaultMutableTreeNode) value).getUserObject();
                if (o instanceof NodeData) {
                    NodeData d = (NodeData) o;
                    setText(d.label);
                    setIcon(d.dir ? ICON_DIR : ICON_FILE);
                    setToolTipText(d.path);
                }
            }
            return this;
        }
    }

    private static NodeData nodeData(TreePath path) {
        if (path == null) {
            return null;
        }
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode)) {
            return null;
        }
        Object user = ((DefaultMutableTreeNode) last).getUserObject();
        return user instanceof NodeData ? (NodeData) user : null;
    }

    // ------------------------------------------------------------------
    // 控制台输出
    // ------------------------------------------------------------------

    private void buildStyles() {
        styleCmd = attrs(Theme.TERM_CMD, true);
        styleOut = attrs(Theme.TERM_FG, false);
        styleErr = attrs(Theme.TERM_ERR, false);
        styleMsg = attrs(Theme.TERM_MSG, false);
        styleOk = attrs(Theme.TERM_OK, false);
    }

    private static SimpleAttributeSet attrs(Color color, boolean bold) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, bold);
        StyleConstants.setFontFamily(a, Theme.MONO_FAMILY);
        StyleConstants.setFontSize(a, 13);
        return a;
    }

    private void welcome() {
        append("模拟磁盘文件系统（题目五）已就绪\n", styleOk);
        append("模拟磁盘 disk.dat：128 块 × 64 字节；FAT 占第 0、1 块；根目录固定第 2 块；"
                + "坏块 23、49；文件结束符 #\n", styleMsg);
        append("提示：在下方输入命令回车执行，↑ ↓ 翻历史；左侧目录树可双击进入、右键操作。\n\n", styleMsg);
        append(fs.execute("help") + "\n", styleOut);
    }

    private void append(String text, SimpleAttributeSet attrs) {
        StyledDocument doc = terminal.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException ignored) {
            // 插入位置异常时忽略，不影响使用
        }
        terminal.setCaretPosition(doc.getLength());
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String join(String dir, String name) {
        if (dir == null || dir.isEmpty() || "/".equals(dir)) {
            return "/" + name;
        }
        return dir + "/" + name;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    /** 目录树节点数据 */
    private static final class NodeData {
        final String path;
        final String label;
        final boolean dir;

        NodeData(String path, String label, boolean dir) {
            this.path = path;
            this.label = label;
            this.dir = dir;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
