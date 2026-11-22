package ru.pirozhok;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Optimizer {

    public static final List<String> CHECKLIST = List.of(
            "Закрой браузер и тяжёлые программы (Chrome, стримы, торренты)",
            "Включи режим «Максимальная производительность» (Параметры → Система → Питание)",
            "Ноутбук — подключи к зарядке",
            "Включи «Игровой режим» (Параметры → Игры → Игровой режим)",
            "Проверь, что Minecraft/javaw использует дискретную видеокарту (Параметры → Дисплей → Графика)",
            "Обнови драйвер видеокарты",
            "Оставь минимум 10 ГБ свободного места на диске с игрой");

    public static final List<String> BOOST_JVM_FLAGS = List.of(
            "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=100", "-XX:+DisableExplicitGC", "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
            "-XX:+PerfDisableSharedMem");

    private Optimizer() { }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static void runAll(Consumer<String> log, boolean boost) {
        if (!isWindows()) {
            log.accept("✗ Оптимизатор работает только в Windows");
            return;
        }
        long before = freeRamMb();
        log.accept(before >= 0 ? "Свободно ОЗУ до: " + before + " МБ" : "Свободно ОЗУ: не удалось определить");

        System.gc();
        log.accept("✓ Освобождена память самого лаунчера");

        log.accept(flushDns() ? "✓ Кэш DNS сброшен" : "• Кэш DNS: пропущено (нужны права администратора)");

        long freed = cleanUserTemp();
        log.accept("✓ Временные файлы пользователя (старше суток): освобождено " + freed / 1048576 + " МБ");

        if (boost) {
            boolean weak = isWeakPc();
            int rec = recommendedRamGb();
            log.accept("• Тип ПК: " + (weak ? "слабый — профиль максимального FPS" : "обычный")
                    + (rec > 0 ? ", память игры: " + rec + " ГБ" : ""));
            log.accept(applyVideoSettings(Config.GAME, true)
                    ? "✓ Настройки графики для FPS применены (копия: options.txt.pirozhok-backup)"
                    : "✗ Не удалось применить настройки графики");
            log.accept("✓ Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast, ModernFix установятся при запуске игры");
        }

        long after = freeRamMb();
        if (after >= 0) log.accept("Свободно ОЗУ после: " + after + " МБ");

        log.accept("• Windows сама управляет кэшем памяти — больше всего ОЗУ освобождает закрытие программ (см. чек-лист).");
        log.accept("✓ Высокий приоритет будет выставлен процессу Minecraft автоматически при запуске игры.");
    }

    public static boolean setHighPriority(long pid) {
        if (!isWindows()) return false;
        try {
            return exec(15, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-Process -Id " + pid + ").PriorityClass = 'High'") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean flushDns() {
        try { return exec(10, "ipconfig", "/flushdns") == 0; }
        catch (Exception e) { return false; }
    }

    public static long freeRamMb() {
        try {
            String out = capture(15, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory");
            Matcher m = Pattern.compile("\\d+").matcher(out);
            return m.find() ? Long.parseLong(m.group()) / 1024 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static long totalRamMb() {
        try {
            String out = capture(15, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory");
            Matcher m = Pattern.compile("\\d+").matcher(out);
            return m.find() ? Long.parseLong(m.group()) / 1048576 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static int recommendedRamGb() {
        long t = totalRamMb();
        if (t <= 0) return -1;
        if (t <= 4500) return 2;
        if (t <= 8500) return 3;
        if (t <= 16500) return 4;
        return 6;
    }

    public static boolean isWeakPc() {
        long t = totalRamMb();
        return Runtime.getRuntime().availableProcessors() <= 4 || (t > 0 && t <= 8500);
    }

    public static long cleanUserTemp() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (tmp.getNameCount() < 3 || !tmp.getFileName().toString().equalsIgnoreCase("temp")) return 0;
        long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
        long[] freed = {0};
        try (Stream<Path> s = Files.walk(tmp)) {
            s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        long size = Files.size(p);
                        Files.delete(p);
                        freed[0] += size;
                    }
                } catch (IOException | SecurityException ignored) { }
            });
        } catch (IOException | UncheckedIOException ignored) { }
        return freed[0];
    }

    public static boolean applyVideoSettings(Path gameDir, boolean force) {
        try {
            Files.createDirectories(gameDir);
            Path marker = gameDir.resolve(".pirozhok-video-applied");
            if (!force && Files.exists(marker)) return false;

            boolean weak = isWeakPc();
            Map<String, String> want = new LinkedHashMap<>();
            want.put("renderDistance", weak ? "6" : "10");
            want.put("simulationDistance", weak ? "5" : "8");
            want.put("graphicsMode", "0");
            want.put("ao", "false");
            want.put("particles", "2");
            want.put("entityShadows", "false");
            want.put("biomeBlendRadius", "1");
            want.put("mipmapLevels", "2");
            want.put("entityDistanceScaling", weak ? "0.75" : "1.0");

            Path f = gameDir.resolve("options.txt");
            Path bak = gameDir.resolve("options.txt.pirozhok-backup");
            if (Files.exists(f) && !Files.exists(bak)) Files.copy(f, bak, StandardCopyOption.REPLACE_EXISTING);

            List<String> lines = Files.exists(f)
                    ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                int c = lines.get(i).indexOf(':');
                if (c <= 0) continue;
                String key = lines.get(i).substring(0, c);
                if (want.containsKey(key)) {
                    lines.set(i, key + ":" + want.get(key));
                    seen.add(key);
                }
            }
            for (Map.Entry<String, String> e : want.entrySet()) {
                if (!seen.contains(e.getKey())) lines.add(e.getKey() + ":" + e.getValue());
            }
            Files.write(f, lines, StandardCharsets.UTF_8);
            Files.writeString(marker, "applied");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int exec(int timeoutSec, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static String capture(int timeoutSec, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
            try { return new String(p.getInputStream().readAllBytes()); }
            catch (IOException e) { return ""; }
        });
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "";
        }
        return out.get(3, TimeUnit.SECONDS);
    }
                       }
