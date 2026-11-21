package ru.pirozhok.client;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

public final class HudDragManager {
    private final HudRenderer hud;
    private HudElement dragging;
    private int grabX, grabY;

    public HudDragManager(HudRenderer hud) { this.hud = hud; }

    public boolean isDragging(HudElement e) { return dragging == e; }
    public boolean isDragging() { return dragging != null; }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!PirozhokClient.isHudEditorMode()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        int sw = mc.getWindow().getScaledWidth();
        HudElement hit = hud.elementAt(mx, my, sw);
        if (hit == null) return false;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = hit;
            grabX = (int) mx - hit.screenX(sw);
            grabY = (int) my - hit.y;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            hit.custom = false;
            PirozhokClient.saveConfig();
            return true;
        }
        return false;
    }

    public void mouseDragged(double mx, double my, int sw, int sh) {
        if (dragging == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean held = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!held) { mouseReleased(GLFW.GLFW_MOUSE_BUTTON_LEFT); return; }

        int nx = clamp((int) mx - grabX, 0, Math.max(0, sw - dragging.w));
        int ny = clamp((int) my - grabY, 0, Math.max(0, sh - dragging.h));
        dragging.custom = true;
        dragging.setScreenX(nx, sw);
        dragging.y = ny;
    }

    public boolean mouseReleased(int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || dragging == null) return false;
        dragging = null;
        PirozhokClient.saveConfig();
        return true;
    }

    public void cancel() { dragging = null; }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
