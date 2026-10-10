package com.app.dc.service.simulation;

import com.app.common.db.ClickHouseDBUtils;
import com.app.common.db.IDatabaseConnection;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskRow;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.gateway.connector.utils.JsonUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
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
            if (!blank(cli.candidateArtifactUri)) {
                installLocalCandidate(service, cli);
            }
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
            param.optimizationObjective = "FEE_ADJUSTED_PROFIT_FIRST";
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
            Map<String, Object> summary = buildSummary(response);
            System.out.println("LOCAL_BACKTEST_RESULT=" + JsonUtils.Serializer(summary));
            if (!blank(cli.resultFile)) {
                Path output = Paths.get(cli.resultFile).toAbsolutePath().normalize();
                if (output.getParent() != null) {
                    Files.createDirectories(output.getParent());
                }
                Files.write(output,
                        (JsonUtils.Serializer(summary) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                System.out.println("resultFile=" + output);
            }
        } finally {
            context.close();
        }
    }

    private static void installLocalCandidate(BacktestService service, Args cli) throws Exception {
        if (blank(cli.candidateEntryClass)) {
            throw new IllegalArgumentException("trainer.backtest.candidateEntryClass is required for local artifact");
        }
        StrategyCandidateRow candidate = new StrategyCandidateRow();
        candidate.id = "local-codex:" + cli.strategyName + "@" + cli.strategyVersion;
        candidate.strategyName = cli.strategyName;
        candidate.strategyVersion = cli.strategyVersion;
        candidate.category = cli.candidateCategory;
        candidate.scene = cli.candidateScene;
        candidate.generationType = "LOCAL_CODEX";
        candidate.runtimeType = "JAR";
        candidate.artifactUri = Paths.get(cli.candidateArtifactUri).toAbsolutePath().normalize().toString();
        candidate.entryClass = cli.candidateEntryClass;
        candidate.description = "Local Codex pre-admission backtest";
        candidate.parametersJson = cli.candidateParametersJson;
        candidate.payload = cli.candidatePayload;

        Field field = BacktestService.class.getDeclaredField("strategyBacktestTaskDao");
        field.setAccessible(true);
        field.set(service, new LocalCandidateDao(candidate));
        System.out.println("localCandidate=" + candidate.strategyName + "@" + candidate.strategyVersion
                + ", artifact=" + candidate.artifactUri + ", entryClass=" + candidate.entryClass);
    }

    private static Map<String, Object> buildSummary(BacktestModels.BacktestResponse response) {
        BacktestModels.BacktestResult result = response.results == null || response.results.isEmpty()
                ? null : response.results.get(0);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("strategyName", response.strategyName);
        summary.put("strategyVersion", response.strategyVersion);
        summary.put("symbols", response.symbols);
        summary.put("scene", response.scene);
        summary.put("text", response.text);
        summary.put("beginDate", response.beginDate);
        summary.put("endDate", response.endDate);
        summary.put("executionModelVersion", result == null ? "" : result.executionModelVersion);
        summary.put("windowMode", response.windowMode);
        summary.put("sliceCount", response.sliceCount);
        summary.put("fitWindowDays", response.fitWindowDays);
        summary.put("validateWindowDays", response.validateWindowDays);
        summary.put("forwardWindowDays", response.forwardWindowDays);
        summary.put("tradeCount", result == null ? 0 : result.tradeCount);
        summary.put("profitFactor", result == null ? BigDecimal.ZERO : result.profitFactor);
        summary.put("maxDrawdownPct", result == null ? BigDecimal.ZERO : result.maxDrawdownPct);
        summary.put("fitPnl", response.fitPnl);
        summary.put("validatePnl", response.validatePnl);
        summary.put("forwardPnl", response.forwardPnl);
        summary.put("totalPnl", response.totalPnl);
        summary.put("feeAdjustedValidatePnl", response.feeAdjustedValidatePnl);
        summary.put("feeAdjustedForwardPnl", response.feeAdjustedForwardPnl);
        summary.put("minForwardContribution", response.minForwardContribution);
        summary.put("forwardContribution", forwardContribution(result));
        summary.put("oosPass", response.oosPass);
        summary.put("overfitReason", response.overfitReason);
        summary.put("optimizationObjective", response.optimizationObjective);
        summary.put("optimizationTrials", response.trials == null
                ? Collections.emptyList() : response.trials);
        String qualificationReason = qualificationReason(result);
        summary.put("qualified", "qualified".equals(qualificationReason));
        summary.put("qualificationReason", qualificationReason);
        return summary;
    }

    private static String qualificationReason(BacktestModels.BacktestResult result) {
        if (result == null || !BacktestModels.EXECUTION_MODEL_VERSION.equals(result.executionModelVersion)) {
            return "realistic_backtest_missing";
        }
        if (result.oosPass == null || result.oosPass.intValue() != 1) {
            return "oos_not_passed";
        }
        if (result.tradeCount == null || result.tradeCount.intValue() < 20) {
            return "too_few_trades";
        }
        if (result.profitFactor == null || result.profitFactor.doubleValue() < 1.20D) {
            return "profit_factor_too_low";
        }
        if (result.maxDrawdownPct == null || result.maxDrawdownPct.doubleValue() > 0.15D) {
            return "drawdown_too_high";
        }
        if (result.feeAdjustedValidatePnl == null || result.feeAdjustedValidatePnl.doubleValue() <= 0D) {
            return "fee_adjusted_validate_not_positive";
        }
        if (result.feeAdjustedForwardPnl == null || result.feeAdjustedForwardPnl.doubleValue() <= 0D) {
            return "fee_adjusted_forward_not_positive";
        }
        if (forwardContribution(result).compareTo(result.minForwardContribution == null
                ? new BigDecimal("0.20") : result.minForwardContribution) < 0) {
            return "forward_contribution_too_low";
        }
        return "qualified";
    }

    private static BigDecimal forwardContribution(BacktestModels.BacktestResult result) {
        if (result == null || result.totalPnl == null || result.totalPnl.compareTo(BigDecimal.ZERO) <= 0
                || result.feeAdjustedForwardPnl == null) {
            return BigDecimal.ZERO;
        }
        return result.feeAdjustedForwardPnl.divide(result.totalPnl, 6, BigDecimal.ROUND_HALF_UP);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
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
        private final String candidateArtifactUri;
        private final String candidateEntryClass;
        private final String candidateScene;
        private final String candidateCategory;
        private final String candidateParametersJson;
        private final String candidatePayload;
        private final String resultFile;

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
                     boolean listVersionsOnly,
                     String candidateArtifactUri,
                     String candidateEntryClass,
                     String candidateScene,
                     String candidateCategory,
                     String candidateParametersJson,
                     String candidatePayload,
                     String resultFile) {
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
            this.candidateArtifactUri = candidateArtifactUri;
            this.candidateEntryClass = candidateEntryClass;
            this.candidateScene = candidateScene;
            this.candidateCategory = candidateCategory;
            this.candidateParametersJson = candidateParametersJson;
            this.candidatePayload = candidatePayload;
            this.resultFile = resultFile;
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
                    boolValue("trainer.backtest.listVersions", false),
                    value("trainer.backtest.candidateArtifactUri", ""),
                    value("trainer.backtest.candidateEntryClass", ""),
                    value("trainer.backtest.candidateScene", "range"),
                    value("trainer.backtest.candidateCategory", "strategy"),
                    value("trainer.backtest.candidateParametersJson", "{}"),
                    value("trainer.backtest.candidatePayload", "{}"),
                    value("trainer.backtest.resultFile", "")
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

    private static final class LocalCandidateDao implements StrategyBacktestTaskDao {
        private final StrategyCandidateRow candidate;

        private LocalCandidateDao(StrategyCandidateRow candidate) {
            this.candidate = candidate;
        }

        @Override
        public List<StrategyBacktestTaskRow> pullPending(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<StrategyBacktestTaskRow> pullRunnable(int limit, String reclaimRunningBefore) {
            return Collections.emptyList();
        }

        @Override
        public List<StrategyBacktestTaskRow> loadLatest(String taskId, String generationTaskId,
                                                        String candidateId, String strategyName,
                                                        String strategyVersion, String status, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void markRunning(String id) {
        }

        @Override
        public void refreshRunningProgress(String id, String payload) {
        }

        @Override
        public void markSuccess(String id, String payload, boolean publishedLive) {
        }

        @Override
        public void markFailed(String id, String errorMsg) {
        }

        @Override
        public void markSuspended(String id, String reason, String payload, String nextRetryTime) {
        }

        @Override
        public void markRetryReadyNow(String id, String reason) {
        }

        @Override
        public void refreshRecoveryProgress(String id, String payload, String nextRetryTime) {
        }

        @Override
        public StrategyCandidateRow loadCandidate(String strategyName, String strategyVersion) {
            if (candidate.strategyName.equals(strategyName) && candidate.strategyVersion.equals(strategyVersion)) {
                return candidate;
            }
            return null;
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
