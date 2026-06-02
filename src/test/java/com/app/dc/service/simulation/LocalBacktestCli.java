package com.app.dc.service.simulation;

import com.app.common.db.ClickHouseDBUtils;
import com.app.common.db.IDatabaseConnection;
import com.app.dc.po.backtest.BacktestParam;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LocalBacktestCli {

    public static void main(String[] args) throws Exception {
        Args cli = Args.fromSystem();
        Class<?> appClass = Class.forName("SIMSvr");
        ConfigurableApplicationContext context = new SpringApplicationBuilder(appClass)
                .web(WebApplicationType.NONE)
                .properties(
                        "strategy.backtest.task.enabled=false",
                        "spring.main.banner-mode=off"
                )
                .run(args);
        try {
            ensureClickHouseReady(context);
            if (cli.listVersionsOnly) {
                listCandidateVersions(cli.strategyName);
                return;
            }
            BacktestService service = context.getBean(BacktestService.class);
            BacktestParam param = new BacktestParam();
            param.strategyName = cli.strategyName;
            param.strategyVersion = cli.strategyVersion;
            param.symbol = cli.symbol;
            param.symbols = cli.symbol;
            param.text = cli.text;
            param.beginDate = cli.beginDate;
            param.endDate = cli.endDate;
            param.initialCapital = cli.initialCapital;
            param.feeRatePct = cli.feeRatePct;
            param.entryMakerFeeRatePct = cli.entryMakerFeeRatePct;
            param.exitTakerFeeRatePct = cli.exitTakerFeeRatePct;
            param.ignoreSentimentGuard = true;
            param.allowMissingStageAnalysis = true;

            BacktestModels.BacktestResponse response = service.run(
                    param,
                    cli.fitWindowDays,
                    cli.validateWindowDays,
                    cli.forwardWindowDays
            );

            System.out.println("=== LOCAL BACKTEST OK ===");
            System.out.println("strategy=" + response.strategyName + "@" + response.strategyVersion);
            System.out.println("symbols=" + (response.symbols == null ? Arrays.asList(param.symbol) : response.symbols));
            System.out.println("text=" + response.text + ", range=" + response.beginDate + "~" + response.endDate);
            System.out.println("trialCount=" + response.trialCount + ", sliceCount=" + response.sliceCount + ", elapsedMs=" + response.elapsedMs);
            System.out.println("fitPnl=" + response.fitPnl + ", validatePnl=" + response.validatePnl
                    + ", forwardPnl=" + response.forwardPnl + ", totalPnl=" + response.totalPnl);
            System.out.println("bestRank=" + response.bestRank + ", bestParamSetJson=" + response.bestParamSetJson);
        } finally {
            context.close();
        }
    }

    private static void ensureClickHouseReady(ConfigurableApplicationContext context) throws Exception {
        String configPath = context.getEnvironment().getProperty("dbpool.cfg", "./config/DBPoolConfig.ini");
        String sourceName = context.getEnvironment().getProperty("clickhouse.default", "ClickHouse1");
        final Map<String, String> dbConfig = loadClickHouseConfig(configPath);
        final String url = dbConfig.get("CLICKHOUSE.DBUrl_0");
        final String user = dbConfig.get("CLICKHOUSE.DBUsername_0");
        final String password = dbConfig.get("CLICKHOUSE.DBPasswd_0");
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        ClickHouseDBUtils.setDatabaseConnection(new IDatabaseConnection() {
            @Override
            public Connection getConnection() {
                try {
                    return DriverManager.getConnection(url, user, password);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void freeConnection(Connection connection) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        });
        ClickHouseDBUtils.init(sourceName, configPath);
    }

    private static final class Args {
        private final String strategyName;
        private final String strategyVersion;
        private final String symbol;
        private final String text;
        private final String beginDate;
        private final String endDate;
        private final int fitWindowDays;
        private final int validateWindowDays;
        private final int forwardWindowDays;
        private final BigDecimal initialCapital;
        private final BigDecimal feeRatePct;
        private final BigDecimal entryMakerFeeRatePct;
        private final BigDecimal exitTakerFeeRatePct;
        private final boolean listVersionsOnly;

        private Args(String strategyName,
                     String strategyVersion,
                     String symbol,
                     String text,
                     String beginDate,
                     String endDate,
                     int fitWindowDays,
                     int validateWindowDays,
                     int forwardWindowDays,
                     BigDecimal initialCapital,
                     BigDecimal feeRatePct,
                     BigDecimal entryMakerFeeRatePct,
                     BigDecimal exitTakerFeeRatePct,
                     boolean listVersionsOnly) {
            this.strategyName = strategyName;
            this.strategyVersion = strategyVersion;
            this.symbol = symbol;
            this.text = text;
            this.beginDate = beginDate;
            this.endDate = endDate;
            this.fitWindowDays = fitWindowDays;
            this.validateWindowDays = validateWindowDays;
            this.forwardWindowDays = forwardWindowDays;
            this.initialCapital = initialCapital;
            this.feeRatePct = feeRatePct;
            this.entryMakerFeeRatePct = entryMakerFeeRatePct;
            this.exitTakerFeeRatePct = exitTakerFeeRatePct;
            this.listVersionsOnly = listVersionsOnly;
        }

        private static Args fromSystem() {
            return new Args(
                    required("trainer.backtest.strategyName"),
                    value("trainer.backtest.strategyVersion", ""),
                    value("trainer.backtest.symbol", "BTCUSDT"),
                    value("trainer.backtest.text", "15m"),
                    value("trainer.backtest.beginDate", "2024-05-25"),
                    value("trainer.backtest.endDate", "2026-05-25"),
                    intValue("trainer.backtest.fitWindowDays", 120),
                    intValue("trainer.backtest.validateWindowDays", 30),
                    intValue("trainer.backtest.forwardWindowDays", 14),
                    decimalValue("trainer.backtest.initialCapital", "10000"),
                    decimalValue("trainer.backtest.feeRatePct", "0.10"),
                    decimalValue("trainer.backtest.entryMakerFeeRatePct", "0.02"),
                    decimalValue("trainer.backtest.exitTakerFeeRatePct", "0.05"),
                    boolValue("trainer.backtest.listVersions", false)
            );
        }

        private static String required(String key) {
            String value = System.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("missing required system property: " + key);
            }
            return value.trim();
        }

        private static String value(String key, String defaultValue) {
            String value = System.getProperty(key);
            return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
        }

        private static int intValue(String key, int defaultValue) {
            String value = System.getProperty(key);
            return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value.trim());
        }

        private static BigDecimal decimalValue(String key, String defaultValue) {
            return new BigDecimal(value(key, defaultValue));
        }

        private static boolean boolValue(String key, boolean defaultValue) {
            String value = System.getProperty(key);
            return value == null || value.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(value.trim());
        }
    }

    private static void listCandidateVersions(String strategyName) throws Exception {
        Map<String, String> dbConfig = loadClickHouseConfig("./config/DBPoolConfig.ini");
        String url = dbConfig.get("CLICKHOUSE.DBUrl_0");
        String user = dbConfig.get("CLICKHOUSE.DBUsername_0");
        String password = dbConfig.get("CLICKHOUSE.DBPasswd_0");
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(
                     "select strategy_name, strategy_version, create_time "
                             + "from dc.strategy_candidate where strategy_name=? "
                             + "order by create_time desc limit 10")) {
            statement.setString(1, strategyName);
            try (ResultSet rs = statement.executeQuery()) {
                System.out.println("=== CANDIDATE VERSIONS ===");
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    System.out.println(rs.getString(1) + " @ " + rs.getString(2) + " @ " + rs.getString(3));
                }
                if (!found) {
                    System.out.println("(none)");
                }
            }
        }
    }

    private static Map<String, String> loadClickHouseConfig(String path) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                continue;
            }
            int idx = line.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return map;
    }
}
