package ru.pirozhok;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public final class Net {
    public interface Listener { void update(long done, long total); }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private Net() { }

    private static HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PirozhokLauncher/" + Config.VERSION);
    }

    public static String getString(String url) throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(req(url).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + ": " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static JsonElement json(String url) throws IOException {
        return JsonParser.parseString(getString(url));
    }

    public static void download(String url, Path target, Listener l) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<InputStream> r = HTTP.send(req(url).build(), HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + ": " + url);
                long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
                try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (l != null) l.update(done, total);
                    }
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        throw last;
    }
}
