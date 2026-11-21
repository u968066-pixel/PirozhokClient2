package ru.pirozhok.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import ru.pirozhok.client.ClientModule.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Меню клиента: сайдбар с категориями + список модулей / настройки темы. */
public final class PirozhokScreen extends Screen {
    private static final int W = 480, H = 310, SIDE = 120;
    private static Category lastTab = Category.COMBAT;

    private final ModuleManager modules;
    private final List<Row> rows = new ArrayList<>();
    private final float[] tabAnim = new float[Category.values().length];

    private Category tab = lastTab;
    private int px, py, pw, ph, tabsTop, tabH;
    private int viewTop, viewBottom, contentX, contentW, contentH;
    private float scroll, openAnim;
    private long lastNanos;
    private Row dragRow;

    public PirozhokScreen(ModuleManager modules) {
        super(Text.literal("Pirozhok Client"));
        this.modules = modules;
    }

    @Override
    protected void init() {
        pw = Math.min(W, width - 16);
        ph = Math.min(H, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        tabsTop = py + 46;
        tabH = Math.max(14, Math.min(22, (ph - 56) / Category.values().length));
        contentX = px + SIDE + 8;
        contentW = pw - SIDE - 16;
        viewTop = py + 30;
        viewBottom = py + ph - 8;
        rebuild();
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { PirozhokClient.saveConfig(); }

    /* ====================================================== rows */

    private void rebuild() {
        rows.clear();
        scroll = 0;
        if (tab == Category.THEME) {
            rows.add(new HeaderRow("Presets"));
            rows.add(new PresetRow());
            rows.add(new HeaderRow("Accent 1"));
            rows.add(new SliderRow("Red", 0, 255, () -> (Theme.accent1 >> 16) & 255,
                    v -> { Theme.accent1 = (Theme.accent1 & 0x00FFFF) | (v << 16); Theme.preset = -1; }));
            rows.add(new SliderRow("Green", 0, 255, () -> (Theme.accent1 >> 8) & 255,
                    v -> { Theme.accent1 = (Theme.accent1 & 0xFF00FF) | (v << 8); Theme.preset = -1; }));
            rows.add(new SliderRow("Blue", 0, 255, () -> Theme.accent1 & 255,
                    v -> { Theme.accent1 = (Theme.accent1 & 0xFFFF00) | v; Theme.preset = -1; }));
            rows.add(new HeaderRow("Accent 2"));
            rows.add(new SliderRow("Red", 0, 255, () -> (Theme.accent2 >> 16) & 255,
                    v -> { Theme.accent2 = (Theme.accent2 & 0x00FFFF) | (v << 16); Theme.preset = -1; }));
            rows.add(new SliderRow("Green", 0, 255, () -> (Theme.accent2 >> 8) & 255,
                    v -> { Theme.accent2 = (Theme.accent2 & 0xFF00FF) | (v << 8); Theme.preset = -1; }));
            rows.add(new SliderRow("Blue", 0, 255, () -> Theme.accent2 & 255,
                    v -> { Theme.accent2 = (Theme.accent2 & 0xFFFF00) | v; Theme.preset = -1; }));
            rows.add(new HeaderRow("Panels"));
            rows.add(new SliderRow("Background opacity", 0, 255, () -> Theme.bgAlpha, v -> Theme.bgAlpha = v));
        } else {
            List<ClientModule> list = modules.inCategory(tab);
            if (list.isEmpty()) rows.add(new HeaderRow("No modules in this category yet"));
            for (ClientModule m : list) rows.add(new ModuleRow(m));
        }
        contentH = 0;
        for (Row r : rows) contentH += r.height();
    }

    private abstract class Row {
        int x, y, w;
        abstract int height();
        abstract void render(DrawContext c, int mx, int my);
        boolean click(double mx, double my, int button) { return false; }
        void drag(double mx) { }
        boolean hit(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + height(); }
    }

    private final class HeaderRow extends Row {
        private final String text;
        HeaderRow(String text) { this.text = text; }
        @Override int height() { return 16; }
        @Override void render(DrawContext c, int mx, int my) {
            c.drawText(textRenderer, text.toUpperCase(), x + 2, y + 5, Theme.TEXT_DIM, false);
        }
    }

    private final class ModuleRow extends Row {
        private final ClientModule m;
        ModuleRow(ClientModule m) { this.m = m; }
        @Override int height() { return 31; }
        @Override void render(DrawContext c, int mx, int my) {
            boolean hover = hit(mx, my) && my >= viewTop && my <= viewBottom;
            Theme.fillRounded(c, x, y, w, 28, 6, Theme.argb(hover ? 52 : 30, 0xFFFFFF));
            if (m.isEnabled()) Theme.fillRounded(c, x, y + 6, 2, 16, 1, Theme.accent(), Theme.accentSecond());
            c.drawText(textRenderer, m.name, x + 10, y + 5, Theme.TEXT, true);
            c.drawText(textRenderer, m.description, x + 10, y + 16, Theme.TEXT_DIM, false);
            Theme.toggle(c, x + w - 36, y + 8, m.anim);
        }
        @Override boolean click(double mx, double my, int button) { m.toggle(); return true; }
    }

    private final class PresetRow extends Row {
        @Override int height() { return 34; }
        @Override void render(DrawContext c, int mx, int my) {
            int n = Theme.PRESETS.size(), gap = 6;
            int sw = (w - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                Theme.Preset p = Theme.PRESETS.get(i);
                int sx = x + i * (sw + gap);
                Theme.fillRounded(c, sx, y + 4, sw, 24, 7, 0xFF000000 | p.accent1(), 0xFF000000 | p.accent2());
                if (Theme.preset == i) Theme.outlineRounded(c, sx - 1, y + 3, sw + 2, 26, 8, 0xFFFFFFFF);
                int tw = textRenderer.getWidth(p.name());
                c.drawText(textRenderer, p.name(), sx + (sw - tw) / 2, y + 12, Theme.TEXT, true);
            }
        }
        @Override boolean click(double mx, double my, int button) {
            int n = Theme.PRESETS.size(), gap = 6;
            int sw = (w - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                int sx = x + i * (sw + gap);
                if (mx >= sx && mx < sx + sw && my >= y + 4 && my < y + 28) { Theme.applyPreset(i); return true; }
            }
            return false;
        }
    }

    private final class SliderRow extends Row {
        private final String label;
        private final int min, max;
        private final IntSupplier get;
        private final IntConsumer set;
        SliderRow(String label, int min, int max, IntSupplier get, IntConsumer set) {
            this.label = label; this.min = min; this.max = max; this.get = get; this.set = set;
        }
        @Override int height() { return 26; }
        private int trackX() { return x + 2; }
        private int trackW() { return w - 4; }
        @Override void render(DrawContext c, int mx, int my) {
            int v = get.getAsInt();
            c.drawText(textRenderer, label, x + 2, y + 3, Theme.TEXT_DIM, false);
            String vs = String.valueOf(v);
            c.drawText(textRenderer, vs, x + w - 2 - textRenderer.getWidth(vs), y + 3, Theme.TEXT, false);
            float t = (v - min) / (float) (max - min);
            int ty = y + 16, fillW = Math.round(trackW() * t);
            Theme.fillRounded(c, trackX(), ty, trackW(), 4, 2, Theme.argb(50, 0xFFFFFF));
            if (fillW > 0) Theme.fillRounded(c, trackX(), ty, Math.max(4, fillW), 4, 2, Theme.accent(), Theme.accentSecond());
            Theme.fillRounded(c, trackX() + fillW - 4, ty - 2, 8, 8, 4, 0xFFFFFFFF);
        }
        @Override boolean click(double mx, double my, int button) { dragRow = this; drag(mx); return true; }
        @Override void drag(double mx) {
            float t = (float) ((mx - trackX()) / trackW());
            set.accept(Math.round(min + Math.max(0f, Math.min(1f, t)) * (max - min)));
        }
    }

    /* ====================================================== render */

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        openAnim = Math.min(1f, openAnim + dt * 5f);
        float e = 1f - (1f - openAnim) * (1f - openAnim) * (1f - openAnim);
        for (ClientModule m : modules.all()) m.updateAnim(dt);

        c.fill(0, 0, width, height, Theme.argb((int) (120 * e), 0x000000));
        c.getMatrices().push();
        c.getMatrices().translate(0, (1f - e) * 14f, 0);

        // стеклянная панель
        Theme.fillRounded(c, px, py, pw, ph, 10, Theme.argb(Math.min(235, Theme.bgAlpha + 60), 0x0A0A10));
        Theme.outlineRounded(c, px, py, pw, ph, 10, Theme.argb(95, Theme.accent1));
        Theme.fillRounded(c, px + 5, py + 5, SIDE - 5, ph - 10, 8, Theme.argb(20, 0xFFFFFF));

        // лого
        Theme.gradientText(c, textRenderer, "PIROZHOK", px + 16, py + 14);
        c.drawText(textRenderer, "CLIENT", px + 16, py + 25, Theme.TEXT_DIM, false);

        // сайдбар
        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            int ty = tabsTop + i * (tabH + 2), tx = px + 10, tw = SIDE - 15;
            boolean hover = mx >= tx && mx < tx + tw && my >= ty && my < ty + tabH;
            float target = cats[i] == tab ? 1f : hover ? 0.4f : 0f;
            tabAnim[i] += (target - tabAnim[i]) * Math.min(1f, dt * 14f);
            float a = tabAnim[i];
            if (a > 0.02f) Theme.fillRounded(c, tx, ty, tw, tabH, 6, Theme.argb((int) (70 * a), Theme.accent1));
            if (cats[i] == tab) Theme.fillRounded(c, tx + 1, ty + 4, 2, tabH - 8, 1, Theme.accent(), Theme.accentSecond());
            int tc = Theme.lerp(Theme.TEXT_DIM, Theme.TEXT, Math.min(1f, a * 1.4f));
            c.drawText(textRenderer, cats[i].title, tx + 9 + Math.round(a * 3), ty + (tabH - 8) / 2, tc, true);
        }

        // заголовок вкладки
        c.drawText(textRenderer, tab.title, contentX + 2, py + 13, Theme.accent(), true);

        // контент со скроллом
        float maxScroll = Math.max(0, contentH - (viewBottom - viewTop));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        c.enableScissor(contentX - 2, viewTop, contentX + contentW + 2, viewBottom);
        int cy = viewTop - Math.round(scroll);
        for (Row r : rows) {
            r.x = contentX;
            r.y = cy;
            r.w = contentW - (maxScroll > 0 ? 6 : 0);
            if (cy + r.height() >= viewTop && cy <= viewBottom) r.render(c, mx, my);
            cy += r.height();
        }
        c.disableScissor();

        if (maxScroll > 0) {                                  // тонкий скроллбар
            int trackH = viewBottom - viewTop;
            int barH = Math.max(14, Math.round(trackH * trackH / (float) contentH));
            int barY = viewTop + Math.round((trackH - barH) * (scroll / maxScroll));
            Theme.fillRounded(c, contentX + contentW - 2, barY, 3, barH, 1, Theme.argb(120, Theme.accent1));
        }
        c.getMatrices().pop();
    }

    /* ====================================================== input */

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            Category[] cats = Category.values();
            for (int i = 0; i < cats.length; i++) {
                int ty = tabsTop + i * (tabH + 2), tx = px + 10;
                if (mx >= tx && mx < tx + SIDE - 15 && my >= ty && my < ty + tabH) {
                    if (tab != cats[i]) { tab = cats[i]; lastTab = tab; rebuild(); }
                    return true;
                }
            }
            if (my >= viewTop && my <= viewBottom) {
                for (Row r : rows) if (r.hit(mx, my) && r.click(mx, my, button)) return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragRow != null && button == 0) { dragRow.drag(mx); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragRow = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= (float) (vertical * 14);
        return true;
    }
                                  }
