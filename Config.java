package ru.pirozhok.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {
    public static final String MC = "1.21.4";
    public static final String VERSION = "1.0.0";
    public static final Path ROOT = Path.of(
            System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")), ".pirozhok");
    public static final Path GAME = ROOT.resolve("game");
    public static final Path MODS = GAME.resolve("mods");
    private static final Path FILE = ROOT.resolve("launcher.properties");

    public String nick = "Player";
    public int ramGb = 4;
    public boolean closeOnLaunch = false;

    public static Config load() {
        Config c = new Config();
        try {
            if (Files.exists(FILE)) {
                Properties p = new Properties();
                try (var in = Files.newBufferedReader(FILE)) { p.load(in); }
                c.nick = p.getProperty("nick", c.nick);
                c.ramGb = Integer.parseInt(p.getProperty("ramGb", "4"));
                c.closeOnLaunch = Boolean.parseBoolean(p.getProperty("closeOnLaunch", "false"));
            }
        } catch (Exception ignored) { }
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(ROOT);
            Properties p = new Properties();
            p.setProperty("nick", nick);
            p.setProperty("ramGb", String.valueOf(ramGb));
            p.setProperty("closeOnLaunch", String.valueOf(closeOnLaunch));
            try (var out = Files.newBufferedWriter(FILE)) { p.store(out, "Pirozhok Launcher"); }
        } catch (IOException ignored) { }
    }
}
