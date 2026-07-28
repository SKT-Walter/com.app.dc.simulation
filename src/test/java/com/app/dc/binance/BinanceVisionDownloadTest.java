package com.app.dc.binance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BinanceVisionDownloadTest {
    private HttpServer server;
    private String baseUrl;
    private final Map<String, byte[]> responses = new HashMap<String, byte[]>();

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                byte[] body = responses.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    public void monthlyArchiveIsDownloadedCachedAndVerified() throws Exception {
        BinanceVisionArchiveLocator locator = new BinanceVisionArchiveLocator(baseUrl);
        BinanceVisionArchiveLocator.Archive archive =
                locator.monthly("ETHUSDT", "15m", YearMonth.of(2025, 1));
        byte[] zip = zip(csv(epoch("2025-01-01T00:00:00Z")));
        respondArchive(archive, zip, sha256(zip));

        Path cache = Files.createTempDirectory("binance-cache-");
        BinanceVisionArchiveRepository repository = repository(cache);
        Path first = repository.obtain(archive);
        Assert.assertTrue(Files.isRegularFile(first));
        responses.clear();
        Assert.assertEquals(first, repository.obtain(archive));
    }

    @Test
    public void badChecksumIsRejected() throws Exception {
        BinanceVisionArchiveLocator locator = new BinanceVisionArchiveLocator(baseUrl);
        BinanceVisionArchiveLocator.Archive archive =
                locator.monthly("ETHUSDT", "15m", YearMonth.of(2025, 1));
        respondArchive(archive, zip(csv(epoch("2025-01-01T00:00:00Z"))),
                repeat("0", 64));
        try {
            repository(Files.createTempDirectory("binance-bad-")).obtain(archive);
            Assert.fail("bad checksum must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("SHA-256 mismatch"));
        }
    }

    @Test
    public void dailyArchivesAreUsedWhenMonthlyIsMissing() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2025-01-03T12:00:00Z"), ZoneOffset.UTC);
        BinanceVisionArchiveLocator locator = new BinanceVisionArchiveLocator(baseUrl);
        for (int day = 1; day <= 2; day++) {
            String date = "2025-01-0" + day;
            BinanceVisionArchiveLocator.Archive archive =
                    locator.daily("ETHUSDT", "15m", java.time.LocalDate.parse(date));
            byte[] zip = zip(csv(epoch(date + "T00:00:00Z")));
            respondArchive(archive, zip, sha256(zip));
        }
        BinanceAnnualKlineService service = new BinanceAnnualKlineService(locator,
                repository(Files.createTempDirectory("binance-daily-")),
                new BinanceKlineCsvParser(clock), new BinanceAnnualJsonWriter(), clock);

        BinanceAnnualKlineService.MonthResult month =
                service.downloadMonth("ETHUSDT", "15m", YearMonth.of(2025, 1), false, false);
        Assert.assertEquals(2, month.rows.size());
    }

    @Test
    public void parserRejectsInvalidOhlc() throws Exception {
        Path zip = Files.createTempFile("binance-invalid-", ".zip");
        Files.write(zip, zip(csv(epoch("2025-01-01T00:00:00Z"))
                .replace(",105,95,102,", ",99,105,102,")));
        BinanceKlineCsvParser parser = new BinanceKlineCsvParser(
                Clock.fixed(Instant.parse("2025-01-02T00:00:00Z"), ZoneOffset.UTC));
        try {
            parser.parse(zip, "15m");
            Assert.fail("invalid OHLC must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("high price is inconsistent"));
        }
    }

    @Test
    public void corruptZipAndMissingColumnsAreRejected() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2025-01-02T00:00:00Z"), ZoneOffset.UTC);
        BinanceKlineCsvParser parser = new BinanceKlineCsvParser(clock);
        Path corrupt = Files.createTempFile("binance-corrupt-", ".zip");
        Files.write(corrupt, "not-a-zip".getBytes(StandardCharsets.UTF_8));
        try {
            parser.parse(corrupt, "15m");
            Assert.fail("corrupt ZIP must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("no CSV"));
        }

        Path columns = Files.createTempFile("binance-columns-", ".zip");
        Files.write(columns, zip(epoch("2025-01-01T00:00:00Z") + ",100,105\n"));
        try {
            parser.parse(columns, "15m");
            Assert.fail("missing CSV columns must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("expected 12 CSV columns"));
        }
    }

    @Test
    public void archiveWithoutChecksumIsRejected() throws Exception {
        BinanceVisionArchiveLocator locator = new BinanceVisionArchiveLocator(baseUrl);
        BinanceVisionArchiveLocator.Archive archive =
                locator.monthly("ETHUSDT", "15m", YearMonth.of(2025, 1));
        responses.put(archive.getZipUri().getPath(), zip(csv(epoch("2025-01-01T00:00:00Z"))));
        try {
            repository(Files.createTempDirectory("binance-no-checksum-")).obtain(archive);
            Assert.fail("missing checksum must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("checksum is missing"));
        }
    }

    @Test
    public void conflictingDuplicateAndTimeGapAreRejected() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2025-02-01T00:00:00Z"), ZoneOffset.UTC);
        BinanceAnnualKlineService service = new BinanceAnnualKlineService(
                new BinanceVisionArchiveLocator(baseUrl),
                repository(Files.createTempDirectory("binance-normalize-")),
                new BinanceKlineCsvParser(clock), new BinanceAnnualJsonWriter(), clock);
        long firstTime = epoch("2025-01-01T00:00:00Z");
        BinanceKline first = kline(firstTime, "100");
        BinanceKline conflict = kline(firstTime, "101");
        try {
            service.normalizeAndFilter(Arrays.asList(first, conflict), 2025, 900_000L, false);
            Assert.fail("conflicting duplicate must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("conflicting duplicate"));
        }
        try {
            service.normalizeAndFilter(Arrays.asList(first, kline(firstTime + 1_800_000L, "102")),
                    2025, 900_000L, false);
            Assert.fail("time gap must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("time gap"));
        }
    }

    @Test
    public void annualJsonWriterProducesBacktestCompatibleFields() throws Exception {
        BinanceKline row = new BinanceKline(epoch("2025-01-01T00:00:00Z"),
                epoch("2025-01-01T00:14:59Z"), new java.math.BigDecimal("100"),
                new java.math.BigDecimal("105"), new java.math.BigDecimal("95"),
                new java.math.BigDecimal("102"), new java.math.BigDecimal("10"));
        Path output = Files.createTempDirectory("binance-json-").resolve("ETHUSDT_2025.json");
        new BinanceAnnualJsonWriter().write(output, "ETHUSDT", "15m",
                java.util.Collections.singletonList(row), false);
        String json = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        Assert.assertTrue(json.contains("\"SecurityID\":\"ETHUSDT\""));
        Assert.assertTrue(json.contains("\"Info3\":\"15m\""));
        Assert.assertTrue(json.contains("\"Info4\":\"true\""));
    }

    private BinanceVisionArchiveRepository repository(Path cache) {
        return new BinanceVisionArchiveRepository(cache,
                new BinanceVisionHttpClient(2_000, 2_000, 1),
                new Sha256ChecksumVerifier());
    }

    private void respondArchive(BinanceVisionArchiveLocator.Archive archive,
                                byte[] zip, String checksum) {
        responses.put(archive.getZipUri().getPath(), zip);
        responses.put(archive.getChecksumUri().getPath(),
                (checksum + "  " + archive.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zip(String csv) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry = new ZipEntry("data.csv");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(csv.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private String csv(long openTime) {
        return openTime + ",100,105,95,102,10," + (openTime + 899_999L)
                + ",1000,5,4,400,0\n";
    }

    private long epoch(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private BinanceKline kline(long openTime, String close) {
        return new BinanceKline(openTime, openTime + 899_999L,
                new java.math.BigDecimal("100"), new java.math.BigDecimal("105"),
                new java.math.BigDecimal("95"), new java.math.BigDecimal(close),
                new java.math.BigDecimal("10"));
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
