package com.eurogamble;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Locale;

/** "Are you sure?" window for a big /pay. Escape or Cancel = the payment is NOT sent. */
public class ConfirmPayScreen extends Screen {
    private final String command;
    private final String player;
    private final double amount;
    private int mx, my;
    private int yesX, yesY, noX, noY;
    private static final int BW = 104, BH = 20;

    public ConfirmPayScreen(String command, String player, double amount) {
        super(Text.literal("Confirm payment"));
        this.command = command;
        this.player = player;
        this.amount = amount;
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        mx = mouseX;
        my = mouseY;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        GambleConfig cfg = EuroGambleMod.config;
        String sym = cfg == null ? "$" : cfg.currencySymbol;

        c.fillGradient(0, 0, width, height, 0x99000000, 0xC0000000);
        int w = 250, h = 104;
        int x = (width - w) / 2, y = (height - h) / 2;
        long now = System.currentTimeMillis();
        int pulse = (int) (150 + 100 * (0.5 + 0.5 * Math.sin(now / 250.0)));

        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xF0121422);
        c.fill(x + 1, y, x + w - 1, y + 1, 0xF0121422);
        c.fill(x + 1, y + h - 1, x + w - 1, y + h, 0xF0121422);
        outline(c, x, y, x + w, y + h, (pulse << 24) | 0xFFC83D);

        c.fill(x + 1, y + 1, x + w - 1, y + 22, 0xFF1D2133);
        c.fill(x + 1, y + 22, x + w - 1, y + 23, 0xFFFFC83D);
        c.drawText(tr, "CONFIRM PAYMENT", x + 10, y + 8, 0xFFFFC83D, true);

        c.drawText(tr, "Really send", x + 10, y + 30, 0xFF8A90AB, false);
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x + 10, y + 42);
        m.scale(2f, 2f);
        c.drawText(tr, sym + AmountFormat.abbreviate(amount), 0, 0, 0xFFFFFFFF, true);
        m.popMatrix();
        int toX = x + 10 + tr.getWidth(sym + AmountFormat.abbreviate(amount)) * 2 + 8;
        c.drawText(tr, "to " + player + "?", Math.min(toX, x + w - 80), y + 47, 0xFFEAECF8, false);
        c.drawText(tr, "exact: " + exact(amount), x + 10, y + 64, 0xFF5A6080, false);

        yesX = x + 12;
        noX = x + w - 12 - BW;
        yesY = noY = y + h - 28;
        button(c, tr, yesX, yesY, "YES, SEND", 0xFF1F8F52);
        button(c, tr, noX, noY, "CANCEL", 0xFF8A2E3A);
    }

    private void button(DrawContext c, TextRenderer tr, int x, int y, String label, int color) {
        boolean hov = mx >= x && mx < x + BW && my >= y && my < y + BH;
        int col = hov ? lighten(color) : color;
        c.fill(x + 1, y, x + BW - 1, y + BH, col);
        c.fill(x, y + 1, x + BW, y + BH - 1, col);
        c.drawText(tr, label, x + (BW - tr.getWidth(label)) / 2, y + 6, 0xFFFFFFFF, true);
    }

    private static int lighten(int col) {
        int r = Math.min(255, ((col >> 16) & 0xFF) + 30);
        int g = Math.min(255, ((col >> 8) & 0xFF) + 30);
        int b = Math.min(255, (col & 0xFF) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void outline(DrawContext c, int x1, int y1, int x2, int y2, int col) {
        c.fill(x1, y1, x2, y1 + 1, col);
        c.fill(x1, y2 - 1, x2, y2, col);
        c.fill(x1, y1, x1 + 1, y2, col);
        c.fill(x2 - 1, y1, x2, y2, col);
    }

    private static String exact(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        if (mx >= yesX && mx < yesX + BW && my >= yesY && my < yesY + BH) {
            close();
            EuroGambleMod.sendBypassed(command);
        } else if (mx >= noX && mx < noX + BW && my >= noY && my < noY + BH) {
            close();
        }
        return true;
    }
}
