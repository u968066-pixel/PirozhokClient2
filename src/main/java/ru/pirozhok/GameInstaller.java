package ru.pirozhok;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GameInstaller {

    public interface Progress { void update(String status, double fraction); }

    private record Item(String url, Path path, long size) { }

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String JAVA_URL =
            "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse";
    private static final String MOD_FILE = "PirojokVisual.jar";

    private static final Path VERSIONS = Config.ROOT.resolve("versions");
    private static final Path LIBS = Config.ROOT.resolve("libraries");
    private static final Path ASSETS = Config.ROOT.resolve("assets");
    private static final Path NATIVES = Config.ROOT.resolve("natives");
    private static final Path RUNTIME = Config.ROOT.resolve("runtime");

    private final Config cfg;
    private final Progress progress;
    private String fabricId;

    public GameInstaller(Config cfg, Progress progress) {
        this.cfg = cfg;
        this.progress = progress;
    }

    private void report(String s, double f) { progress.update(s, f); }

    public void run() throws Exception {
        Files.createDirectories(Config.MODS);
        Files.createDirectories(NATIVES);

        String java = ensureJava();
        report("Получение данных Mojang и Fabric…", 0.24);
        JsonObject vanilla = fetchVanilla();
        JsonObject fabric = fetchFabric();

        Map<Path, Item> items = new LinkedHashMap<>();
        Set<Path> classpath = new LinkedHashSet<>();

        for (JsonElement e : vanilla.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            if (!allowed(lib.getAsJsonArray("rules"))) continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("artifact")) continue;
            JsonObject art = downloads.getAsJsonObject("artifact");
            Path p = LIBS.resolve(art.get("path").getAsString());
            items.putIfAbsent(p, new Item(art.get("url").getAsString(), p, art.get("size").getAsLong()));
            classpath.add(p);
        }
        for (JsonElement e : fabric.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            String base = lib.has("url") ? lib.get("url").getAsString() : "https://maven.fabricmc.net/";
            if (!base.endsWith("/")) base += "/";
            String rel = mavenPath(lib.get("name").getAsString());
            Path p = LIBS.resolve(rel);
            items.putIfAbsent(p, new Item(base + rel, p, -1));
            classpath.add(p);
        }
        Path clientJar = VERSIONS.resolve(Config.MC).resolve(Config.MC + ".jar");
        JsonObject client = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
        items.putIfAbsent(clientJar, new Item(client.get("url").getAsString(), clientJar, client.get("size").getAsLong()));
        classpath.add(clientJar);

        downloadAll(new ArrayList<>(items.values()), "Скачивание библиотек", 0.25, 0.45);

        JsonObject assetIndex = vanilla.getAsJsonObject("assetIndex");
        String assetId = assetIndex.get("id").getAsString();
        Path indexFile = ASSETS.resolve("indexes").resolve(assetId + ".json");
        if (!Files.exists(indexFile)) Net.download(assetIndex.get("url").getAsString(), indexFile, null);
        JsonObject objects = parse(indexFile).getAsJsonObject("objects");
        List<Item> assets = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : objects.entrySet()) {
            JsonObject o = en.getValue().getAsJsonObject();
            String hash = o.get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            Path p = ASSETS.resolve("objects").resolve(prefix).resolve(hash);
            assets.add(new Item("https://resources.download.minecraft.net/" + prefix + "/" + hash, p, o.get("size").getAsLong()));
        }
        downloadAll(assets, "Скачивание ресурсов", 0.45, 0.92);

        report("Установка модов…", 0.93);
        boolean modOk = installMod();
        installFabricApi();
        if (cfg.boost) {
            installBoostMods();
            Optimizer.applyVideoSettings(Config.GAME, false);
        }

        report(modOk ? "Запуск Minecraft…" : "Мод PirojokVisual.jar не найден в libs — запуск без него…", 0.97);
        launch(java, vanilla, fabric, classpath, assetId);
        report("Игра запущена!", 1.0);
    }

    private String ensureJava() throws Exception {
        Path own = RUNTIME.resolve("jre21").resolve("bin").resolve("javaw.exe");
        if (Files.exists(own)) return own.toString();

        String sys = systemJava();
        if (sys != null) return sys;

        Path zip = RUNTIME.resolve("jre21.zip");
        Net.download(JAVA_URL, zip, (d, t) -> report(
                "Скачивание Java 21… " + mb(d) + (t > 0 ? " / " + mb(t) : ""),
                0.02 + 0.18 * (t > 0 ? (double) d / t : 0)));
        report("Распаковка Java 21…", 0.21);
        Path tmp = RUNTIME.resolve("jre21.tmp");
        deleteRecursive(tmp);
        unzipStrip(zip, tmp);
        deleteRecursive(RUNTIME.resolve("jre21"));
        Files.move(tmp, RUNTIME.resolve("jre21"), StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(zip);
        return own.toString();
    }

    private String systemJava() {
        List<String> candidates = new ArrayList<>();
        String home = System.getenv("JAVA_HOME");
        if (home != null) candidates.add(Path.of(home, "bin", "java.exe").toString());
        candidates.add("java");
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                Matcher m = Pattern.compile("version \"(\\d+)").matcher(out);
                if (m.find() && Integer.parseInt(m.group(1)) >= 21) {
                    return c.equals("java") ? "javaw" : c.replace("java.exe", "javaw.exe");
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static void unzipStrip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        Path base = dest.toAbsolutePath().normalize();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                int i = name.indexOf('/');
                if (i < 0) continue;
                String rel = name.substring(i + 1);
                if (rel.isEmpty()) continue;
                Path out = base.resolve(rel).normalize();
                if (!out.startsWith(base)) throw new IOException("Bad zip entry: " + name);
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private JsonObject fetchVanilla() throws Exception {
        Path f = VERSIONS.resolve(Config.MC).resolve(Config.MC + ".json");
        if (!Files.exists(f)) {
            JsonObject manifest = Net.json(MANIFEST).getAsJsonObject();
            String url = null;
            for (JsonElement e : manifest.getAsJsonArray("versions")) {
                JsonObject o = e.getAsJsonObject();
                if (o.get("id").getAsString().equals(Config.MC)) { url = o.get("url").getAsString(); break; }
            }
            if (url == null) throw new IOException("Версия " + Config.MC + " не найдена в манифесте Mojang");
            Net.download(url, f, null);
        }
        return parse(f);
    }

    private JsonObject fetchFabric() throws Exception {
        JsonArray arr = Net.json("https://meta.fabricmc.net/v2/versions/loader/" + Config.MC).getAsJsonArray();
        String lv = null;
        for (JsonElement e : arr) {
            JsonObject l = e.getAsJsonObject().getAsJsonObject("loader");
            if (l.get("stable").getAsBoolean()) { lv = l.get("version").getAsString(); break; }
        }
        if (lv == null) lv = arr.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
        fabricId = "fabric-loader-" + lv + "-" + Config.MC;
        Path f = VERSIONS.resolve(fabricId).resolve(fabricId + ".json");
        if (!Files.exists(f)) {
            Net.download("https://meta.fabricmc.net/v2/versions/loader/" + Config.MC + "/" + lv + "/profile/json", f, null);
        }
        return parse(f);
    }

    private static JsonObject parse(Path f) throws IOException {
        return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String mavenPath(String name) {
        String[] p = name.split(":");
        String g = p[0].replace('.', '/');
        String cls = p.length > 3 ? "-" + p[3] : "";
        return g + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + cls + ".jar";
    }

    private void downloadAll(List<Item> all, String label, double from, double to) throws Exception {
        List<Item> todo = new ArrayList<>();
        for (Item i : all) {
            boolean have = Files.exists(i.path()) && (i.size() < 0 || Files.size(i.path()) == i.size());
            if (!have) todo.add(i);
        }
        if (todo.isEmpty()) { report(label + ": всё уже скачано", to); return; }

        ExecutorService pool = Executors.newFixedThreadPool(12);
        AtomicInteger done = new AtomicInteger();
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Item i : todo) {
                futures.add(pool.submit(() -> {
                    Net.download(i.url(), i.path(), null);
                    int d = done.incrementAndGet();
                    report(label + " (" + d + "/" + todo.size() + ")", from + (to - from) * d / todo.size());
                    return null;
                }));
            }
            for (Future<Object> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ee) {
                    throw ee.getCause() instanceof Exception ex ? ex : ee;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String mb(long b) { return String.format(Locale.US, "%.1f МБ", b / 1048576.0); }

    private boolean installMod() {
        Path src = findMod();
        if (src == null) return false;
        try {
            Files.createDirectories(Config.MODS);
            Files.copy(src, Config.MODS.resolve(MOD_FILE), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findMod() {
        List<Path> dirs = new ArrayList<>();
        try {
            Path jar = Path.of(GameInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            dirs.add(dir);
            dirs.add(dir.resolve("libs"));
        } catch (Exception ignored) { }
        dirs.add(Path.of("libs"));
        dirs.add(Path.of("."));
        for (Path d : dirs) {
            Path c = d.resolve(MOD_FILE);
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    private static final String[][] BOOST_MODS = {
            {"sodium", "sodium"}, {"lithium", "lithium"}, {"ferrite-core", "ferritecore"},
            {"entityculling", "entityculling"}, {"immediatelyfast", "immediatelyfast"}, {"modernfix", "modernfix"}};

    private void installFabricApi() { installModrinth("fabric-api", "fabric-api"); }

    private void installBoostMods() {
        for (int i = 0; i < BOOST_MODS.length; i++) {
            report("FPS-буст: установка " + BOOST_MODS[i][0] + "…", 0.935 + 0.03 * i / BOOST_MODS.length);
            installModrinth(BOOST_MODS[i][0], BOOST_MODS[i][1]);
        }
    }

    private void installModrinth(String slug, String filePrefix) {
        try (Stream<Path> s = Files.list(Config.MODS)) {
            if (s.anyMatch(p -> p.getFileName().toString().toLowerCase().startsWith(filePrefix))) return;
        } catch (IOException ignored) { }
        try {
            String q = "https://api.modrinth.com/v2/project/" + slug + "/version"
                    + "?game_versions=%5B%22" + Config.MC + "%22%5D&loaders=%5B%22fabric%22%5D";
            JsonArray versions = Net.json(q).getAsJsonArray();
            if (versions.isEmpty()) return;
            JsonArray files = versions.get(0).getAsJsonObject().getAsJsonArray("files");
            JsonObject file = files.get(0).getAsJsonObject();
            for (JsonElement e : files) {
                if (e.getAsJsonObject().get("primary").getAsBoolean()) { file = e.getAsJsonObject(); break; }
            }
            Net.download(file.get("url").getAsString(), Config.MODS.resolve(file.get("filename").getAsString()), null);
        } catch (Exception ignored) { }
    }

    private void launch(String java, JsonObject vanilla, JsonObject fabric, Set<Path> cp, String assetId) throws Exception {
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + cfg.nick).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", cfg.nick);
        vars.put("version_name", fabricId);
        vars.put("game_directory", Config.GAME.toString());
        vars.put("assets_root", ASSETS.toString());
        vars.put("assets_index_name", assetId);
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("version_type", "release");
        vars.put("natives_directory", NATIVES.toString());
        vars.put("launcher_name", "PirozhokLauncher");
        vars.put("launcher_version", Config.VERSION);
        vars.put("classpath", cp.stream().map(Path::toString).collect(Collectors.joining(";")));

        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        int ram = cfg.ramGb;
        if (cfg.boost) {
            int rec = Optimizer.recommendedRamGb();
            if (rec > 0) ram = rec;
        }
        cmd.add("-Xmx" + ram + "G");
        cmd.add("-Xms" + Math.min(ram, 2) + "G");
        if (cfg.boost) cmd.addAll(Optimizer.BOOST_JVM_FLAGS);
        cmd.addAll(args(vanilla, "jvm", vars));
        cmd.addAll(args(fabric, "jvm", vars));
        cmd.add(fabric.get("mainClass").getAsString());
        cmd.addAll(args(vanilla, "game", vars));
        cmd.addAll(args(fabric, "game", vars));

        Path log = Config.GAME.resolve("logs").resolve("launcher-latest.log");
        Files.createDirectories(log.getParent());
        Process p = new ProcessBuilder(cmd)
                .directory(Config.GAME.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();

        if (cfg.highPriority) {
            boolean ok = Optimizer.setHighPriority(p.pid());
            report(ok ? "Приоритет Minecraft: High" : "High-приоритет не выставлен (не критично)", 0.99);
        }

        Thread.sleep(4000);
        if (!p.isAlive()) {
            throw new IOException("Игра закрылась сразу (код " + p.exitValue() + "). Лог: " + log);
        }
    }

    private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)}");

    private static String sub(String s, Map<String, String> vars) {
        return VAR.matcher(s).replaceAll(m -> Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
    }

    private static List<String> args(JsonObject version, String kind, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        JsonObject a = version.getAsJsonObject("arguments");
        if (a == null || !a.has(kind)) return out;
        for (JsonElement e : a.getAsJsonArray(kind)) {
            if (e.isJsonPrimitive()) {
                out.add(sub(e.getAsString(), vars));
            } else {
                JsonObject o = e.getAsJsonObject();
                if (!allowed(o.getAsJsonArray("rules"))) continue;
                JsonElement v = o.get("value");
                if (v.isJsonArray()) for (JsonElement x : v.getAsJsonArray()) out.add(sub(x.getAsString(), vars));
                else out.add(sub(v.getAsString(), vars));
            }
        }
        return out;
    }

    private static boolean allowed(JsonArray rules) {
        if (rules == null) return true;
        boolean ok = false;
        for (JsonElement e : rules) {
            JsonObject r = e.getAsJsonObject();
            boolean applies = !r.has("features");
            if (r.has("os")) {
                JsonObject os = r.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals("windows")) applies = false;
                if (os.has("arch")) applies = false;
            }
            if (applies) ok = r.get("action").getAsString().equals("allow");
        }
        return ok;
    }
    }
