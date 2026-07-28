package com.app.dc.binance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Writes the minimal MarketDataSnapshotFullRefresh-compatible annual JSON array. */
public final class BinanceAnnualJsonWriter {
    public Path write(Path output, String symbol, String interval, List<BinanceKline> rows,
                      boolean overwrite) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        if (Files.exists(normalized) && !overwrite)
            throw new IOException("annual market data already exists; use --overwrite=true: " + normalized);
        Path partial = normalized.resolveSibling(normalized.getFileName().toString() + ".partial");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                writer.write("[\n");
                for (int i = 0; i < rows.size(); i++) {
                    BinanceKline k = rows.get(i);
                    writer.write("  {\"SecurityID\":\"");
                    writer.write(json(symbol));
                    writer.write("\",\"OpenPrice\":\"");
                    writer.write(k.getOpen().toPlainString());
                    writer.write("\",\"HighPrice\":\"");
                    writer.write(k.getHigh().toPlainString());
                    writer.write("\",\"LowPrice\":\"");
                    writer.write(k.getLow().toPlainString());
                    writer.write("\",\"ClosePrice\":\"");
                    writer.write(k.getClose().toPlainString());
                    writer.write("\",\"Volume\":\"");
                    writer.write(k.getVolume().toPlainString());
                    writer.write("\",\"Info1\":\"");
                    writer.write(String.valueOf(k.getOpenTime()));
                    writer.write("\",\"Info3\":\"");
                    writer.write(json(interval));
                    writer.write("\",\"Info4\":\"true\"}");
                    if (i + 1 < rows.size()) writer.write(',');
                    writer.newLine();
                }
                writer.write("]\n");
            }
            move(partial, normalized, overwrite);
            return normalized;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private void move(Path source, Path target, boolean overwrite) throws IOException {
        try {
            if (overwrite)
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            if (overwrite) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        }
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
