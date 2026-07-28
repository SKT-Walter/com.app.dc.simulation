package com.app.dc.binance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Downloads, caches and verifies one Binance Vision archive. */
public final class BinanceVisionArchiveRepository {
    private final Path cacheDirectory;
    private final BinanceVisionHttpClient httpClient;
    private final Sha256ChecksumVerifier checksumVerifier;

    public BinanceVisionArchiveRepository(Path cacheDirectory, BinanceVisionHttpClient httpClient,
                                          Sha256ChecksumVerifier checksumVerifier) {
        this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
        this.httpClient = httpClient;
        this.checksumVerifier = checksumVerifier;
    }

    /** Returns a verified ZIP path, or null when the archive itself is HTTP 404. */
    public Path obtain(BinanceVisionArchiveLocator.Archive archive) throws IOException {
        Path zip = safeCachePath(archive.getRelativePath());
        Path checksum = zip.resolveSibling(zip.getFileName().toString() + ".CHECKSUM");
        if (Files.isRegularFile(zip) && Files.isRegularFile(checksum)) {
            checksumVerifier.verify(zip, checksum);
            return zip;
        }

        if (!httpClient.download(archive.getZipUri(), zip)) return null;
        if (!httpClient.download(archive.getChecksumUri(), checksum))
            throw new IOException("archive exists but checksum is missing: " + archive.getChecksumUri());
        checksumVerifier.verify(zip, checksum);
        return zip;
    }

    private Path safeCachePath(String relative) throws IOException {
        Path target = cacheDirectory.resolve(relative.replace('/', java.io.File.separatorChar))
                .toAbsolutePath().normalize();
        if (!target.startsWith(cacheDirectory))
            throw new IOException("unsafe Binance cache path: " + relative);
        return target;
    }
}
