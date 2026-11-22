package ru.pirozhok.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/** Цвета темы (Glassmorphism + неон) и хелперы рисования скруглённых панелей. */
public final class Theme {

    public record Preset(String name, int accent1, int accent2) { }

    public static final List<Preset> PRESETS = List.of(
            new Preset("Neon", 0x22D3EE, 0x8B5CF6),
            new Preset("Ocean", 0x38BDF8, 0x2563EB),
            new Preset("Sunset", 0xFB7185, 0xF59E0B),
            new Preset("Mint", 0x34D399, 0x22D3EE),
            new Preset("Candy", 0xF472B6, 0xA78BFA));

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFA1A1B5;

    /** RGB без альфы. */
    public static int accent1 = 0x22D3EE;
    public static int accent2 = 0x8B5CF6;
    /** Прозрачность тёмной подложки 0..255. */
    public static int bgAlpha = 150;
    /** Индекс пресета или -1, если цвета изменены вручную. */
    public static int preset = 0;

    private Theme() { }

    public static void applyPreset(int i) {
        Preset p = PRESETS.get(i);
        accent1 = p.accent1();
        accent2 = p.accent2();
        preset = i;
    }

    /* ------------------------------------------------ colors */

    public static int accent() { return 0xFF000000 | accent1; }
    public static int accentSecond() { return 0xFF000000 | accent2; }
    public static int accentAt(float t) { return lerp(accent(), accentSecond(), t); }
    /** Тёмная полупрозрачная подложка HUD. */
    public static int panel() { return argb(bgAlpha, 0x0B0B12); }

    public static int argb(int a, int rgb) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((c1 >>> 24) & 255) * (1 - t) + ((c2 >>> 24) & 255) * t);
        int r = Math.round(((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
        int g = Math.round(((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
        int b = Math.round((c1 & 255) * (1 - t) + (c2 & 255) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /* ------------------------------------------------ drawing */

    private static int inset(int row, int r) {
        double d = r - row - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - d * d)));
    }

    public static void fillRounded(DrawContext c, int x, int y, int w, int h, int r, int color) {
        fillRounded(c, x, y, w, h, r, color, color);
    }

    /** Скруглённый прямоугольник с вертикальным градиентом top -> bottom. */
    public static void fillRounded(DrawContext c, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        boolean grad = top != bottom;
        for (int i = 0; i < r; i++) {
            int in = inset(i, r);
            int cTop = grad ? lerp(top, bottom, (i + 0.5f) / h) : top;
            int cBot = grad ? lerp(top, bottom, (h - 1 - i + 0.5f) / h) : top;
            c.fill(x + in, y + i, x + w - in, y + i + 1, cTop);
            c.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, cBot);
        }
        if (!grad) {
            c.fill(x, y + r, x + w, y + h - r, top);
        } else {
            for (int row = r; row < h - r; row++) {
                c.fill(x, y + row, x + w, y + row + 1, lerp(top, bottom, (row + 0.5f) / h));
            }
        }
    }

    /** Тонкая (1 px) скруглённая рамка. */
    public static void outlineRounded(DrawContext c, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        c.fill(x + r, y, x + w - r, y + 1, color);
        c.fill(x + r, y + h - 1, x + w - r, y + h, color);
        c.fill(x, y + r, x + 1, y + h - r, color);
        c.fill(x + w - 1, y + r, x + w, y + h - r, color);
        int prev = r;
        for (int i = 0; i < r; i++) {
            int in = inset(i, r);
            int end = Math.max(in + 1, prev);
            c.fill(x + in, y + i, x + end, y + i + 1, color);
            c.fill(x + w - end, y + i, x + w - in, y + i + 1, color);
            c.fill(x + in, y + h - 1 - i, x + end, y + h - i, color);
            c.fill(x + w - end, y + h - 1 - i, x + w - in, y + h - i, color);
            prev = in;
        }
    }

    /** Овальный переключатель 26x12; anim: 0 = выкл, 1 = вкл. */
    public static void toggle(DrawContext c, int x, int y, float anim) {
        int off = 0xFF3A3A4A;
        fillRounded(c, x, y, 26, 12, 6, lerp(off, accent(), anim), lerp(off, accentSecond(), anim));
        if (anim > 0.02f) outlineRounded(c, x - 1, y - 1, 28, 14, 7, argb((int) (70 * anim), accent1));
        int kx = x + 2 + Math.round(anim * 14);
        fillRounded(c, kx, y + 2, 8, 8, 4, 0xFFFFFFFF);
    }

    /** Текст с градиентом accent1 -> accent2, возвращает ширину. */
    public static int gradientText(DrawContext c, TextRenderer tr, String s, int x, int y) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            float t = s.length() <= 1 ? 0f : (float) i / (s.length() - 1);
            c.drawText(tr, ch, cx, y, accentAt(t), true);
            cx += tr.getWidth(ch);
        }
        return cx - x;
    }
          }
