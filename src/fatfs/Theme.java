package fatfs;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 界面主题：统一配色、字体，以及圆角卡片、按钮、徽章等自绘组件。
 * 所有界面类共用这里的颜色常量，保证风格一致。
 */
final class Theme {
    private Theme() {
    }

    // ---------------------------------------------------------------- 配色
    static final Color BG = new Color(0xF1F4FA);          // 窗口底色
    static final Color CARD = Color.WHITE;                // 卡片底色
    static final Color BORDER = new Color(0xDCE3EE);      // 卡片描边
    static final Color TEXT = new Color(0x1F2937);        // 主文字
    static final Color MUTED = new Color(0x6B7280);       // 次要文字
    static final Color FAINT = new Color(0x9CA3AF);       // 提示文字

    static final Color PRIMARY = new Color(0x2563EB);     // 主色
    static final Color PRIMARY_HOVER = new Color(0x3B82F6);
    static final Color PRIMARY_PRESS = new Color(0x1D4ED8);
    static final Color PRIMARY_BG = new Color(0xE8F0FE);  // 次要按钮底色
    static final Color PRIMARY_BG_HOVER = new Color(0xD6E3FD);
    static final Color PRIMARY_BG_PRESS = new Color(0xC3D7FC);
    static final Color PRIMARY_DARK = new Color(0x1D4ED8);

    static final Color SUCCESS = new Color(0x15803D);
    static final Color DANGER = new Color(0xB91C1C);
    static final Color WHITE = Color.WHITE;

    // ------------------------------------------------------------ 终端配色
    static final Color TERM_BG = new Color(0x0F172A);
    static final Color TERM_BORDER = new Color(0x1E293B);
    static final Color TERM_FG = new Color(0xE2E8F0);
    static final Color TERM_CMD = new Color(0x60A5FA);    // 命令回显
    static final Color TERM_ERR = new Color(0xFCA5A5);    // 错误输出
    static final Color TERM_MSG = new Color(0x94A3B8);    // 系统提示
    static final Color TERM_OK = new Color(0x86EFAC);     // 成功提示
    static final Color TERM_HINT = new Color(0x64748B);   // 输入框占位
    static final Color TERM_INPUT_BG = new Color(0x1E293B);
    static final Color TERM_INPUT_BORDER = new Color(0x334155);

    // ------------------------------------------------------------ 盘块配色
    static final Color BLK_SYS = new Color(0x6366F1);     // 系统区 0~2
    static final Color BLK_FREE = new Color(0xE4E9F2);    // 空闲
    static final Color BLK_USED = new Color(0x3B82F6);    // 已分配（文件）
    static final Color BLK_DIR = new Color(0x10B981);     // 目录块
    static final Color BLK_BAD = new Color(0xEF4444);     // 坏块

    // ---------------------------------------------------------------- 字体
    static final String UI_FAMILY = pick(
            new String[]{"Microsoft YaHei UI", "微软雅黑", "Microsoft YaHei", "Segoe UI",
                    "PingFang SC", "Noto Sans CJK SC"}, Font.SANS_SERIF);
    static final String MONO_FAMILY = pick(
            new String[]{"Consolas", "Cascadia Mono", "JetBrains Mono", "Sarasa Mono SC",
                    "Menlo", "DejaVu Sans Mono"}, Font.MONOSPACED);

    static Font ui(int style, int size) {
        return new Font(UI_FAMILY, style, size);
    }

    static Font mono(int style, int size) {
        return new Font(MONO_FAMILY, style, size);
    }

    /** 从候选列表里挑第一个系统里存在的字体，都没有则用逻辑字体 */
    private static String pick(String[] prefer, String fallback) {
        try {
            List<String> avail = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames());
            for (String p : prefer) {
                for (String a : avail) {
                    if (a.equalsIgnoreCase(p)) {
                        return a;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 无图形环境（如 headless）时退回逻辑字体
        }
        return fallback;
    }

    // ------------------------------------------------------------ 全局外观
    static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认外观
        }
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource) {
                FontUIResource f = (FontUIResource) value;
                UIManager.put(key, new FontUIResource(UI_FAMILY, f.getStyle(), f.getSize()));
            }
        }
        UIManager.put("OptionPane.messageFont", new FontUIResource(UI_FAMILY, Font.PLAIN, 13));
        UIManager.put("OptionPane.buttonFont", new FontUIResource(UI_FAMILY, Font.PLAIN, 13));
        UIManager.put("Tree.rowHeight", 24);
    }

    // ------------------------------------------------------------ 自绘组件

    /** 圆角卡片：白色（或深色）圆角底 + 细描边 + 左上方小标题 */
    static final class Card extends JPanel {
        private final JLabel titleLabel = new JLabel();
        private final JPanel header = new JPanel(new java.awt.BorderLayout());
        private Color fill = CARD;
        private Color line = BORDER;

        Card(String title) {
            super(new java.awt.BorderLayout(0, 8));
            setOpaque(false);
            setBorder(new EmptyBorder(12, 14, 14, 14));
            titleLabel.setText(title);
            titleLabel.setFont(ui(Font.BOLD, 13));
            titleLabel.setForeground(TEXT);
            header.setOpaque(false);
            header.add(titleLabel, java.awt.BorderLayout.WEST);
            add(header, java.awt.BorderLayout.NORTH);
        }

        void setTitle(String title) {
            titleLabel.setText(title);
        }

        void setHeaderRight(Component c) {
            header.add(c, java.awt.BorderLayout.EAST);
        }

        /** 深色卡片（用于命令行控制台） */
        void setDark() {
            fill = TERM_BG;
            line = TERM_BORDER;
            titleLabel.setForeground(TERM_FG);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.setColor(line);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
        }
    }

    /** 圆角按钮：实心（主操作）或淡蓝（次要操作），带悬停/按下反馈 */
    static final class RoundedButton extends JButton {
        private final Color base;
        private final Color hover;
        private final Color press;
        private boolean hovering;

        RoundedButton(String text, Color base, Color hover, Color press, Color fg, int radius) {
            super(text);
            this.base = base;
            this.hover = hover;
            this.press = press;
            setForeground(fg);
            setFont(ui(Font.PLAIN, 13));
            setBorder(new EmptyBorder(7, 14, 7, 14));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            putClientProperty("radius", radius);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovering = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovering = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int radius = (Integer) getClientProperty("radius");
            Color c = base;
            if (!isEnabled()) {
                c = new Color(0xE5E7EB);
            } else if (getModel().isPressed()) {
                c = press;
            } else if (hovering) {
                c = hover;
            }
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static RoundedButton primaryButton(String text) {
        return new RoundedButton(text, PRIMARY, PRIMARY_HOVER, PRIMARY_PRESS, WHITE, 10);
    }

    static RoundedButton softButton(String text) {
        return new RoundedButton(text, PRIMARY_BG, PRIMARY_BG_HOVER, PRIMARY_BG_PRESS, PRIMARY_DARK, 10);
    }

    /** 圆角徽章（顶部的磁盘占用 / 打开文件数） */
    static final class Pill extends JLabel {
        private final Color fill;

        Pill(String text, Color fg, Color fill) {
            super(text);
            this.fill = fill;
            setForeground(fg);
            setFont(ui(Font.BOLD, 12));
            setOpaque(false);
            setBorder(new EmptyBorder(5, 12, 5, 12));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 圆角边框 */
    static final class RoundBorder implements Border {
        private final Color color;
        private final int radius;
        private final Insets insets;

        RoundBorder(Color color, int radius, int padH, int padV) {
            this.color = color;
            this.radius = radius;
            this.insets = new Insets(padV, padH, padV, padH);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return insets;
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g2.dispose();
        }
    }

    /** 带占位提示的输入框（提示文字用界面字体绘制，避免等宽字体缺中文字形） */
    static final class HintField extends JTextField {
        private String hint = "";
        private Color hintColor = FAINT;

        void setHint(String hint, Color hintColor) {
            this.hint = hint == null ? "" : hint;
            this.hintColor = hintColor;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty() && !hint.isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(hintColor);
                g2.setFont(ui(Font.PLAIN, Math.max(12, getFont().getSize() - 1)));
                Insets ins = getInsets();
                int y = (getHeight() + g2.getFontMetrics().getAscent()
                        - g2.getFontMetrics().getDescent()) / 2;
                g2.drawString(hint, ins.left, y);
                g2.dispose();
            }
        }
    }

    /** 小方块图标（目录树、图例共用） */
    static final class BlockIcon implements Icon {
        private final Color color;
        private final int size;

        BlockIcon(Color color) {
            this(color, 10);
        }

        BlockIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y, size, size, 4, 4);
            g2.dispose();
        }
    }

    /** 去掉滚动面板默认白边，避免破坏卡片圆角 */
    static javax.swing.JScrollPane scroll(Component view) {
        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(view);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }
}
