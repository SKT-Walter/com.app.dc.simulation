package com.app.dc.simulation;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestQueryService;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.service.simulation.BacktestService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Standalone local backtest entry point; no gateway or SIMSvr process is started. */
public final class LocalBacktestRunner {
    private LocalBacktestRunner() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            printHelp();
            return;
        }
        BacktestParam param = toParam(options);
        int chunkDays = integer(options, "chunkDays", 2, 1, 20);
        Path projectDir = resolveProjectDir(options);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(LocalApp.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.location="
                        + projectDir.resolve("config/application.properties").toUri().toString())
                .run(springArguments(projectDir, "LOCAL.BACKTEST"));
        try {
            BacktestService service = context.getBean(BacktestService.class);
            BacktestReportService reports = context.getBean(BacktestReportService.class);
            BacktestQueryService query = context.getBean(BacktestQueryService.class);
            BacktestModels.BacktestResponse response = "all".equalsIgnoreCase(param.strategyName)
                    ? service.run(param)
                    : isDeterministic(param.strategyName)
                    ? service.runDeterministicChunked(param, chunkDays)
                    : service.runContinuousChunked(param, chunkDays);
            if (response.results == null || response.results.isEmpty())
                throw new IllegalStateException("backtest produced no results");
            String report = reports.writeReport(response);
            System.out.println("\n=== Local Backtest Summary ===");
            System.out.println("strategy=" + response.strategyName + ", symbols=" + response.symbols + ", timeframe=" + response.text + ", range=" + response.beginDate + ".." + response.endDate);
            for (String symbol : response.symbols)
                System.out.println("dataSource[" + symbol + "]=" + query.getLastSource(symbol));
            for (BacktestModels.BacktestResult r : response.results)
                System.out.println("result strategy=" + r.strategyName + ", symbol=" + r.symbol
                        + ", actualRange=" + r.actualBeginTime + ".." + r.actualEndTime
                        + ", bars=" + r.totalBars + ", trades=" + r.tradeCount
                        + ", tradeNotional=" + money(r.tradeNotional)
                        + ", return=" + percent(r.totalReturnPct)
                        + ", maxDrawdown=" + percent(r.maxDrawdownPct)
                        + ", finalCapital=" + money(r.finalCapital));
            System.out.println("report=" + report);
        } finally {
            context.close();
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (args == null) return out;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                out.put("help", "true");
                continue;
            }
            if (arg == null || !arg.startsWith("--") || arg.indexOf('=') < 3)
                throw new IllegalArgumentException("argument must use --name=value: " + arg);
            int p = arg.indexOf('=');
            String k = arg.substring(2, p), v = arg.substring(p + 1);
            if (v.trim().isEmpty()) throw new IllegalArgumentException("argument value is empty: " + k);
            out.put(k, v.trim());
        }
        return out;
    }

    private static boolean isDeterministic(String strategy) {
        return "dynamic".equalsIgnoreCase(strategy) || "deterministic".equalsIgnoreCase(strategy);
    }

    static BacktestParam toParam(Map<String, String> o) {
        require(o, "begin");
        require(o, "end");
        LocalDate begin = date(o.get("begin")), end = date(o.get("end"));
        if (end.isBefore(begin)) throw new IllegalArgumentException("--end must not be before --begin");
        BacktestParam p = new BacktestParam();
        p.strategyName = o.getOrDefault("strategy", "all");
        p.symbols = o.getOrDefault("symbols", "ETHUSDT");
        p.symbol = p.symbols.split("[,|\\s]")[0];
        p.text = o.getOrDefault("text", "15m");
        p.beginDate = begin.toString();
        p.endDate = end.toString();
        p.initialCapital = decimal(o, "capital", "10000");
        p.tradeNotional = decimal(o, "tradeNotional", "10000");
        p.feeRatePct = decimal(o, "fee", "0.04");
        p.fallbackStopLossPct = decimal(o, "stopLoss", "6");
        p.fallbackTakeProfitPct = decimal(o, "takeProfit", "7");
        p.maxHoldBars = integer(o, "maxHoldBars", 0, 0, Integer.MAX_VALUE);
        p.ignoreSentimentGuard = bool(o, "ignoreSentimentGuard", true);
        return p;
    }

    private static void require(Map<String, String> o, String k) {
        if (!o.containsKey(k)) throw new IllegalArgumentException("missing required argument --" + k);
    }

    private static LocalDate date(String v) {
        try {
            return LocalDate.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("date must use yyyy-MM-dd: " + v, e);
        }
    }

    private static BigDecimal decimal(Map<String, String> o, String k, String d) {
        try {
            BigDecimal v = new BigDecimal(o.getOrDefault(k, d));
            if (v.signum() < 0) throw new Exception();
            return v;
        } catch (Exception e) {
            throw new IllegalArgumentException("--" + k + " must be a non-negative number");
        }
    }

    private static int integer(Map<String, String> o, String k, int d, int min, int max) {
        try {
            int v = Integer.parseInt(o.getOrDefault(k, String.valueOf(d)));
            if (v < min || v > max) throw new Exception();
            return v;
        } catch (Exception e) {
            throw new IllegalArgumentException("--" + k + " must be between " + min + " and " + max);
        }
    }

    private static boolean bool(Map<String, String> o, String k, boolean d) {
        String v = o.getOrDefault(k, String.valueOf(d));
        if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v))
            throw new IllegalArgumentException("--" + k + " must be true or false");
        return Boolean.parseBoolean(v);
    }

    private static void printHelp() {
        System.out.println("Usage: --strategy=all|deterministic|策略名 --symbols=ETHUSDT,BTCUSDT --text=15m "
                + "--begin=2026-01-01 --end=2026-01-31 [--capital=10000 --tradeNotional=10000 --fee=0.04 --stopLoss=6 "
                + "--takeProfit=7 --maxHoldBars=0 --chunkDays=2 --ignoreSentimentGuard=true --projectDir=...]");
    }

    /** Resolves simulator paths independently of the IDE's configured working directory. */
    static Path resolveProjectDir(Map<String, String> options) throws Exception {
        if (options != null && options.containsKey("projectDir")) {
            Path explicit = Paths.get(options.get("projectDir")).toAbsolutePath().normalize();
            validateProjectDir(explicit);
            return explicit;
        }
        Path codeLocation = Paths.get(LocalBacktestRunner.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        Path found = findProjectDir(codeLocation);
        if (found == null) found = findProjectDir(Paths.get("").toAbsolutePath().normalize());
        if (found == null)
            throw new IllegalStateException("cannot locate com.app.dc.simulation project; use --projectDir=<absolute path>");
        return found;
    }

    private static Path findProjectDir(Path start) {
        Path current = Files.isDirectory(start) ? start : start.getParent();
        for (int i = 0; current != null && i < 8; i++, current = current.getParent())
            if (isProjectDir(current)) return current;
        return null;
    }

    private static void validateProjectDir(Path path) {
        if (!isProjectDir(path)) throw new IllegalArgumentException("invalid simulator project directory: " + path);
    }

    private static boolean isProjectDir(Path path) {
        return path != null
                && Files.isRegularFile(path.resolve("config/application.properties"))
                && Files.isRegularFile(path.resolve("config/dynamic_strategy_catalog.json"));
    }

    static String[] springArguments(Path projectDir, String serverKey) {
        return new String[]{
                "--serverKey=" + serverKey,
                "--binanceBacktestStageGuardEnabled=false",
                "--binanceBacktestSentimentGuardEnabled=false",
                "--backtest.dynamic.catalogFile=" + absolute(projectDir, "config/dynamic_strategy_catalog.json"),
                "--backtest.localDataDir=" + absolute(projectDir, "config/data"),
                "--binanceBacktestReportDir=" + absolute(projectDir, "src/docs/backtest"),
                "--dbpool.cfg=" + absolute(projectDir, "config/DBPoolConfig.ini"),
                "--log4j.file=" + absolute(projectDir, "config/log4j.ini")
        };
    }

    private static String absolute(Path projectDir, String relative) {
        return projectDir.resolve(relative).toAbsolutePath().normalize().toString();
    }

    private static String percent(BigDecimal ratio) {
        return ratio == null ? "" : String.format(Locale.ROOT, "%.4f%%", ratio.doubleValue() * 100.0);
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.2f", value.doubleValue());
    }

    @SpringBootApplication
    @ComponentScan({"com.app.dc.service.simulation", "com.app.dc.service.dao", "com.app.common.db"})
    public static class LocalApp {
    }
}
