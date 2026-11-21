package ru.pirozhok.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class PirozhokClient implements ClientModInitializer {
    public static final String MOD_ID = "pirojokvisual";

    public static KeyBinding menuKey;

    private static PirozhokClient instance;
    /** true, пока открыт чат (T): HUD можно перетаскивать. */
    private static boolean hudEditorMode;

    private ModuleManager modules;
    private HudRenderer hud;
    private PirozhokConfig config;

    public static boolean isHudEditorMode() { return hudEditorMode; }
    public static void saveConfig() { if (instance != null) instance.config.save(); }

    @Override
    public void onInitializeClient() {
        instance = this;
        modules = new ModuleManager();
        hud = new HudRenderer(modules);
        config = new PirozhokConfig(modules, hud);
        config.load();

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pirojokvisual.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.pirojokvisual"));

        hud.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // Мышь в чате: старт/конец перетаскивания (возврат false отменяет обычный клик чата)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) ->
                    !hud.drag().mouseClicked(mx, my, button));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, mx, my, button) -> {
                hud.drag().mouseReleased(button);
                return true;
            });
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> config.save());
    }

    private void onTick(MinecraftClient mc) {
        // Нажали T (или /) -> открылся чат -> режим редактирования HUD. Закрыли чат / Esc -> выключили и сохранили.
        boolean chatOpen = mc.currentScreen instanceof ChatScreen;
        if (chatOpen != hudEditorMode) {
            hudEditorMode = chatOpen;
            if (!chatOpen) {
                hud.drag().cancel();
                config.save();
            }
        }

        while (menuKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new PirozhokScreen(modules));
        }
        hud.tick(mc);
    }
}
