package ru.pirozhok.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import ru.pirozhok.client.HudElement.Line;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Отрисовка HUD (Glassmorphism) + режим редактирования. */
public final class HudRenderer {
    private static final int PAD = 5, LINE_H = 11, GAP = 12, RADIUS = 5, MARGIN = 6, STACK_GAP = 4;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String MC_VERSION = "1.21.4";

    private final ModuleManager modules;
    private final List<HudElement> elements = new ArrayList<>();
    private final HudDragManager drag = new HudDragManager(this);
    private final Deque<Long> clicks = new ArrayDeque<>();
    private boolean attackWasDown;

    public HudRenderer(ModuleManager modules) {
        this.modules = modules;
        // Слева сверху (порядок = порядок авто-раскладки)
        add("watermark", "Watermark", false, mc ->
                List.of(new Line("PIROZHOK", Line.GRADIENT, MC_VERSION, Theme.TEXT_DIM)));
        add("fps", "FPS", false, mc -> List.of(Line.pair("FPS", String.valueOf(mc.getCurrentFps()))));
        add("ping", "Ping", false, this::pingLines);
        add("time", "Time", false, mc -> List.of(Line.pair("Time", LocalTime.now().format(CLOCK))));
        add("coords", "Coordinates", false, this::coordLines);
        add("keybinds", "Keybinds", false, this::keybindLines);
        add("active_modules", "Active Modules", false, this::activeModuleLines);
        add("effects", "Effects", false, this::effectLines);
        add("armor_status", "Armor Status", false, this::armorLines);
        add("cps", "CPS", false, mc -> List.of(Line.pair("CPS", String.valueOf(cps()))));
        add("arrow_counter", "Arrows", false, mc -> List.of(Line.pair("Arrows", String.valueOf(countArrows(mc)))));
        // Справа сверху
        add("potion_list", "Potion List", true, this::potionLines);
    }

    private void add(String id, String title, boolean right, java.util.function.Function<MinecraftClient, List<Line>> f) {
        elements.add(new HudElement(id, title, modules.get(id), right, f));
    }

    public List<HudElement> elements() { return elements; }
    public HudDragManager drag() { return drag; }

    public void register() { HudRenderCallback.EVENT.register(this::render); }

    /** Раз в тик: счётчик кликов для CPS (только отображение). */
    public void tick(MinecraftClient mc) {
        boolean down = mc.options.attackKey.isPressed();
        long now = System.currentTimeMillis();
        if (down && !attackWasDown) clicks.addLast(now);
        attackWasDown = down;
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000) clicks.pollFirst();
    }

    private int cps() { return clicks.size(); }

    /* ====================================================== render */

    public void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        boolean editor = PirozhokClient.isHudEditorMode();
        if (!editor && (mc.options.hudHidden || mc.options.debugEnabled)) return;

        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        TextRenderer tr = mc.textRenderer;
        layout(mc, tr, sw, editor);

        HudElement hovered = null;
        if (editor) {
            double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
            double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
            drag.mouseDragged(mx, my, sw, sh);
            hovered = elementAt(mx, my, sw);
        }

        boolean outline = modules.get("panel_outline").isEnabled();
        boolean glow = modules.get("panel_glow").isEnabled();
        for (HudElement e : elements) {
            if (!e.rendered) continue;
            drawElement(ctx, tr, e, sw, outline, glow, editor, e == hovered || drag.isDragging(e));
        }
        if (editor) drawEditorHint(ctx, tr, sw);
    }

    /** Измеряет блоки и раскладывает те, которые игрок ещё не двигал. */
    private void layout(MinecraftClient mc, TextRenderer tr, int sw, boolean editor) {
        int leftY = MARGIN, rightY = MARGIN;
        for (HudElement e : elements) {
            e.rendered = false;
            if (!e.visible()) continue;
            List<Line> lines = e.lines(mc);
            if (lines.isEmpty()) {
                if (!editor) continue;                        // в редакторе пустой блок можно найти и двигать
                lines = List.of(Line.text(e.title, Theme.TEXT_DIM));
            }
            int w = 0;
            for (Line l : lines) {
                int lw = tr.getWidth(l.left()) + (l.right() != null ? GAP + tr.getWidth(l.right()) : 0);
                w = Math.max(w, lw);
            }
            e.cached = lines;
            e.w = w + PAD * 2;
            e.h = lines.size() * LINE_H + PAD * 2 - 2;
            e.rendered = true;

            if (!e.custom) {
                e.x = MARGIN;
                if (e.anchorRight) { e.y = rightY; rightY += e.h + STACK_GAP; }
                else { e.y = leftY; leftY += e.h + STACK_GAP; }
            }
        }
    }

    private void drawElement(DrawContext ctx, TextRenderer tr, HudElement e, int sw,
                             boolean outline, boolean glow, boolean editor, boolean active) {
        int x = e.screenX(sw), y = e.y;
        Theme.fillRounded(ctx, x, y, e.w, e.h, RADIUS, Theme.panel());
        if (glow) Theme.outlineRounded(ctx, x - 1, y - 1, e.w + 2, e.h + 2, RADIUS + 1, Theme.argb(45, Theme.accent2));
        if (outline) Theme.outlineRounded(ctx, x, y, e.w, e.h, RADIUS, Theme.argb(110, Theme.accent1));

        int ty = y + PAD;
        for (Line l : e.cached) {
            if (l.leftColor() == Line.GRADIENT) Theme.gradientText(ctx, tr, l.left(), x + PAD, ty);
            else ctx.drawText(tr, l.left(), x + PAD, ty, l.leftColor() == Line.DEFAULT ? Theme.TEXT : l.leftColor(), true);
            if (l.right() != null) {
                int rc = l.rightColor() == Line.DEFAULT ? Theme.accent() : l.rightColor();
                ctx.drawText(tr, l.right(), x + e.w - PAD - tr.getWidth(l.right()), ty, rc, true);
            }
            ty += LINE_H;
        }

        if (editor) {                                         // тонкая неоновая рамка
            int a = (int) (170 + 70 * Math.sin(System.currentTimeMillis() / 250.0));
            if (active) {
                Theme.fillRounded(ctx, x - 2, y - 2, e.w + 4, e.h + 4, RADIUS + 2, Theme.argb(38, Theme.accent1));
                Theme.outlineRounded(ctx, x - 3, y - 3, e.w + 6, e.h + 6, RADIUS + 3, Theme.argb(120, Theme.accent2));
            }
            Theme.outlineRounded(ctx, x - 2, y - 2, e.w + 4, e.h + 4, RADIUS + 2, Theme.argb(a, Theme.accent1));
        }
    }

    private void drawEditorHint(DrawContext ctx, TextRenderer tr, int sw) {
        String s = "HUD editor  •  drag with LMB  •  RMB resets position  •  Esc to save";
        int w = tr.getWidth(s) + 16, x = (sw - w) / 2;
        Theme.fillRounded(ctx, x, 4, w, 16, 7, Theme.panel());
        Theme.outlineRounded(ctx, x, 4, w, 16, 7, Theme.argb(120, Theme.accent1));
        ctx.drawText(tr, s, x + 8, 8, Theme.TEXT, true);
    }

    public HudElement elementAt(double mx, double my, int sw) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement e = elements.get(i);
            if (!e.rendered) continue;
            int x = e.screenX(sw);
            if (mx >= x - 2 && mx <= x + e.w + 2 && my >= e.y - 2 && my <= e.y + e.h + 2) return e;
        }
        return null;
    }

    /* ====================================================== content */

    private List<Line> pingLines(MinecraftClient mc) {
        String v;
        if (mc.isInSingleplayer()) v = "local";
        else {
            PlayerListEntry entry = mc.getNetworkHandler() == null ? null
                    : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            v = entry == null ? "--" : entry.getLatency() + " ms";
        }
        return List.of(Line.pair("Ping", v));
    }

    private List<Line> coordLines(MinecraftClient mc) {
        return List.of(
                Line.pair("X
