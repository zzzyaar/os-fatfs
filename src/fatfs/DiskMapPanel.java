package fatfs;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.HashSet;
import java.util.Set;

/**
 * 磁盘盘块占用图：把 128 个盘块的 FAT 状态画成 16 列小方块，
 * 颜色区分系统区 / 空闲 / 已分配 / 目录块 / 坏块，鼠标悬停显示块详情。
 */
class DiskMapPanel extends JPanel {
    private static final int COLS = 16;
    private static final int ROWS = (Disk.BLOCK_COUNT + COLS - 1) / COLS;

    private int[] fat = new int[Disk.BLOCK_COUNT];
    private Set<Integer> dirBlocks = new HashSet<Integer>();
    private int hover = -1;

    DiskMapPanel() {
        setOpaque(false);
        setPreferredSize(new Dimension(420, 250));
        setToolTipText("");
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int now = indexAt(e.getX(), e.getY());
                if (now != hover) {
                    hover = now;
                    repaint();
                }
            }
        });
    }

    void setData(int[] fatSnapshot, Set<Integer> directoryBlocks) {
        fat = fatSnapshot;
        dirBlocks = directoryBlocks == null ? new HashSet<Integer>() : directoryBlocks;
        repaint();
    }

    // ------------------------------------------------------------ 布局计算

    /** 返回 {cellW, cellH, gap, y0}；y0 为网格垂直居中后的起始 y */
    private int[] grid() {
        int w = getWidth();
        int h = getHeight();
        int gap = 3;
        int cellW = Math.max(8, (w - gap * (COLS - 1)) / COLS);
        int cellH = Math.max(8, (h - gap * (ROWS - 1)) / ROWS);
        cellH = Math.min(cellH, (int) (cellW * 0.92));
        int totalH = cellH * ROWS + gap * (ROWS - 1);
        int y0 = Math.max(0, (h - totalH) / 2);
        return new int[]{cellW, cellH, gap, y0};
    }

    private int indexAt(int x, int y) {
        int[] g = grid();
        int cellW = g[0];
        int cellH = g[1];
        int gap = g[2];
        int y0 = g[3];
        int col = x / (cellW + gap);
        int row = (y - y0) / (cellH + gap);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) {
            return -1;
        }
        int idx = row * COLS + col;
        return idx >= 0 && idx < Disk.BLOCK_COUNT ? idx : -1;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void paintComponent(Graphics g) {
        int[] ge = grid();
        int cellW = ge[0];
        int cellH = ge[1];
        int gap = ge[2];
        int y0 = ge[3];

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int fontSize = Math.max(8, Math.min(11, cellW / 3));
        g2.setFont(Theme.mono(Font.PLAIN, fontSize));
        FontMetrics fm = g2.getFontMetrics();

        for (int i = 0; i < Disk.BLOCK_COUNT; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = col * (cellW + gap);
            int y = y0 + row * (cellH + gap);

            g2.setColor(colorOf(i));
            g2.fillRoundRect(x, y, cellW, cellH, 5, 5);

            if (i == hover) {
                g2.setColor(Theme.WHITE);
                g2.drawRoundRect(x, y, cellW - 1, cellH - 1, 5, 5);
            }

            if (cellW >= 18 && cellH >= 12) {
                String t = String.valueOf(i);
                g2.setColor(textColorOf(i));
                g2.drawString(t, x + (cellW - fm.stringWidth(t)) / 2,
                        y + (cellH + fm.getAscent() - fm.getDescent()) / 2);
            }
        }
        g2.dispose();
    }

    private Color colorOf(int block) {
        int v = fat[block];
        if (block < Disk.FIRST_DATA_BLOCK) {
            return Theme.BLK_SYS;
        }
        if (v == Disk.BAD) {
            return Theme.BLK_BAD;
        }
        if (v == Disk.FREE) {
            return Theme.BLK_FREE;
        }
        return dirBlocks.contains(block) ? Theme.BLK_DIR : Theme.BLK_USED;
    }

    private Color textColorOf(int block) {
        return colorOf(block).equals(Theme.BLK_FREE) ? Theme.MUTED : Theme.WHITE;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int idx = indexAt(e.getX(), e.getY());
        if (idx < 0) {
            return null;
        }
        int v = fat[idx];
        String state;
        if (idx < Disk.FIRST_DATA_BLOCK) {
            state = "系统区（FAT / 根目录）";
        } else if (v == Disk.BAD) {
            state = "坏块，不参与分配";
        } else if (v == Disk.FREE) {
            state = "空闲";
        } else if (dirBlocks.contains(idx)) {
            state = "目录块，下一块 " + fatText(v);
        } else {
            state = "文件块，下一块 " + fatText(v);
        }
        return "第 " + idx + " 块　FAT = " + fatText(v) + "　" + state;
    }

    private static String fatText(int v) {
        if (v == Disk.EOF) {
            return "255（结束）";
        }
        if (v == Disk.BAD) {
            return "254（坏块）";
        }
        if (v == Disk.FREE) {
            return "0（空闲）";
        }
        return String.valueOf(v);
    }

    // ---------------------------------------------------------------- 图例

    static JPanel legend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 2));
        p.setOpaque(false);
        p.add(item(Theme.BLK_SYS, "系统区"));
        p.add(item(Theme.BLK_USED, "文件块"));
        p.add(item(Theme.BLK_DIR, "目录块"));
        p.add(item(Theme.BLK_FREE, "空闲"));
        p.add(item(Theme.BLK_BAD, "坏块"));
        return p;
    }

    private static JPanel item(Color color, String text) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        p.setOpaque(false);
        p.add(new JLabel(new Theme.BlockIcon(color, 10)));
        JLabel l = new JLabel(text);
        l.setFont(Theme.ui(Font.PLAIN, 11));
        l.setForeground(Theme.MUTED);
        p.add(l);
        return p;
    }
}
