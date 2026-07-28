package com.app.dc.binance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Verifies Binance .CHECKSUM files against downloaded archives. */
public final class Sha256ChecksumVerifier {
    public void verify(Path archive, Path checksumFile) throws IOException {
        if (!Files.isRegularFile(checksumFile))
            throw new IOException("checksum file is missing: " + checksumFile);
        String content = new String(Files.readAllBytes(checksumFile), "UTF-8").trim();
        String expected = content.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}"))
            throw new IOException("invalid SHA-256 checksum content: " + checksumFile);
        String actual = sha256(archive);
        if (!expected.equals(actual))
            throw new IOException("SHA-256 mismatch for " + archive.getFileName()
                    + ", expected " + expected + " but got " + actual);
    }

    String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0)
                    if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
