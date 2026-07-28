package com.app.dc.binance;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small Java 8 HTTP downloader with bounded retries and atomic cache writes. */
@Slf4j
public final class BinanceVisionHttpClient {
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxAttempts;

    public BinanceVisionHttpClient(int connectTimeoutMillis, int readTimeoutMillis, int maxAttempts) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * Downloads a URI to the target. Returns false only for HTTP 404.
     * All other terminal responses fail the operation.
     */
    public boolean download(URI uri, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Path partial = target.resolveSibling(target.getFileName().toString() + ".partial");
            try {
                Files.deleteIfExists(partial);
                int status = downloadOnce(uri, partial);
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    Files.deleteIfExists(partial);
                    return false;
                }
                if (status >= 200 && status < 300) {
                    moveReplace(partial, target);
                    return true;
                }
                if (status != 429 && status < 500)
                    throw new IOException("HTTP " + status + " downloading " + uri);
                throw new IOException("retryable HTTP " + status + " downloading " + uri);
            } catch (IOException e) {
                Files.deleteIfExists(partial);
                last = e;
                if (attempt == maxAttempts) break;
                long delay = Math.min(4_000L, 250L * (1L << (attempt - 1)));
                log.warn("Binance Vision download failed, retry {}/{}, uri:{}, reason:{}",
                        attempt, maxAttempts, uri, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("download interrupted: " + uri, interrupted);
                }
            }
        }
        throw last == null ? new IOException("download failed: " + uri) : last;
    }

    private int downloadOnce(URI uri, Path partial) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "com.app.dc.simulation-binance-vision/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return status;
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    private void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
