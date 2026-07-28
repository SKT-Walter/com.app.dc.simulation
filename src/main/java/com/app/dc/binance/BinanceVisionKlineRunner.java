package com.app.dc.binance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Standalone CLI for downloading annual USD-M futures klines from Binance Vision. */
public final class BinanceVisionKlineRunner {
    private static final String DEFAULT_BASE_URL = "https://data.binance.vision";
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private BinanceVisionKlineRunner() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            printHelp();
            return;
        }
        List<String> symbols = symbols(required(options, "symbols"));
        String interval = options.containsKey("text") ? options.get("text").trim() : "15m";
        BinanceInterval.durationMillis(interval);
        int year = integer(required(options, "year"), "year");
        boolean overwrite = bool(options.get("overwrite"), false, "overwrite");
        Path projectDir = resolveProjectDir(options.get("projectDir"));
        Path dataDir = projectDir.resolve("config/data").toAbsolutePath().normalize();
        Path cacheDir = options.containsKey("cacheDir")
                ? Paths.get(options.get("cacheDir")).toAbsolutePath().normalize()
                : dataDir.resolve(".binance-cache");
        String baseUrl = options.containsKey("baseUrl") ? options.get("baseUrl") : DEFAULT_BASE_URL;
        Path log4j = projectDir.resolve("config/log4j.ini");
        if (Files.isRegularFile(log4j))
            org.apache.log4j.PropertyConfigurator.configure(log4j.toString());

        Clock clock = Clock.systemUTC();
        BinanceVisionArchiveLocator locator = new BinanceVisionArchiveLocator(baseUrl);
        BinanceVisionHttpClient http = new BinanceVisionHttpClient(10_000, 60_000, 3);
        BinanceVisionArchiveRepository repository =
                new BinanceVisionArchiveRepository(cacheDir, http, new Sha256ChecksumVerifier());
        BinanceAnnualKlineService service = new BinanceAnnualKlineService(locator, repository,
                new BinanceKlineCsvParser(clock), new BinanceAnnualJsonWriter(), clock);

        System.out.println("Binance Vision U本位合约年度K线下载");
        System.out.println("币种: " + symbols + ", 周期: " + interval + ", 年份: " + year);
        System.out.println("缓存目录: " + cacheDir);
        for (String symbol : symbols) {
            Path output = dataDir.resolve(symbol + "_" + year + ".json");
            BinanceAnnualKlineService.DownloadResult result =
                    service.download(symbol, interval, year, output, overwrite);
            System.out.println(String.format(Locale.ROOT,
                    "%s 完成: %,d 根, %s 至 %s, 文件: %s",
                    result.symbol, result.bars,
                    DISPLAY_TIME.format(Instant.ofEpochMilli(result.firstOpenTime)),
                    DISPLAY_TIME.format(Instant.ofEpochMilli(result.lastOpenTime)),
                    result.output));
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        if (args == null) return options;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
                continue;
            }
            if (arg == null || !arg.startsWith("--") || arg.indexOf('=') < 3)
                throw new IllegalArgumentException("arguments must use --name=value: " + arg);
            int equals = arg.indexOf('=');
            String key = arg.substring(2, equals);
            String value = arg.substring(equals + 1);
            if (options.put(key, value) != null)
                throw new IllegalArgumentException("duplicate argument: --" + key);
        }
        return options;
    }

    private static List<String> symbols(String value) {
        List<String> result = new ArrayList<String>();
        for (String part : value.split(",")) {
            String symbol = part.trim().toUpperCase(Locale.ROOT);
            if (!symbol.matches("[A-Z0-9]{5,30}"))
                throw new IllegalArgumentException("invalid Binance symbol: " + part);
            if (!result.contains(symbol)) result.add(symbol);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("--symbols must not be empty");
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("--" + key + " is required");
        return value;
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer", e);
        }
    }

    private static boolean bool(String value, boolean defaultValue, String name) {
        if (value == null) return defaultValue;
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
            throw new IllegalArgumentException("--" + name + " must be true or false");
        return Boolean.parseBoolean(value);
    }

    private static Path resolveProjectDir(String explicit) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            Path path = Paths.get(explicit).toAbsolutePath().normalize();
            validateProjectDir(path);
            return path;
        }
        try {
            Path code = Paths.get(BinanceVisionKlineRunner.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path found = findProjectDir(code);
            if (found == null) found = findProjectDir(Paths.get("").toAbsolutePath().normalize());
            if (found != null) return found;
        } catch (Exception e) {
            throw new IllegalStateException("cannot locate simulator project directory", e);
        }
        throw new IllegalStateException("cannot locate simulator project; use --projectDir=<absolute path>");
    }

    private static Path findProjectDir(Path start) {
        Path current = Files.isDirectory(start) ? start : start.getParent();
        for (int i = 0; current != null && i < 8; i++, current = current.getParent())
            if (isProjectDir(current)) return current;
        return null;
    }

    private static void validateProjectDir(Path path) {
        if (!isProjectDir(path))
            throw new IllegalArgumentException("invalid simulator project directory: " + path);
    }

    private static boolean isProjectDir(Path path) {
        return path != null && Files.isRegularFile(path.resolve("pom.xml"))
                && Files.isDirectory(path.resolve("src/main/java/com/app/dc"));
    }

    private static void printHelp() {
        System.out.println("Usage: --symbols=ETHUSDT,BTCUSDT --text=15m --year=2025 "
                + "[--overwrite=true --projectDir=... --cacheDir=...]");
    }
}
