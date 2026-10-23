package com.app.dc.service.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.scene.DeepSeekSceneTimelineService;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test-only market input adapter for local formal SIMSvr backtests. */
final class LocalResearchMarketData {
    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter BAR_TIME = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");
    private static final String PROMPT_VERSION = "market_scene_v3_historical_kline_v1";

    private LocalResearchMarketData() {
    }

    static void install(BacktestService service, Path inputDir, String symbol, String text,
                        String beginDate, String endDate, String barsSha256,
                        String scenesSha256) throws Exception {
        if (!ZoneId.systemDefault().getRules().isFixedOffset()
                || !ZoneOffset.UTC.equals(ZoneId.systemDefault().getRules().getOffset(Instant.now()))) {
            throw new IllegalStateException("local formal replay requires -Duser.timezone=UTC");
        }
        if (symbol == null || !symbol.matches("[A-Z0-9]+")
                || !"15m".equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("local research input requires one 15m symbol");
        }
        Path barsPath = inputDir.resolve(symbol + "-15m.tsv");
        Path scenesPath = inputDir.resolve(symbol + "-scenes.tsv");
        byte[] barBytes = Files.readAllBytes(barsPath);
        byte[] sceneBytes = Files.readAllBytes(scenesPath);
        requireHash(barBytes, barsSha256, barsPath);
        requireHash(sceneBytes, scenesSha256, scenesPath);
        List<TTbookOhlc> bars = loadBars(barBytes, symbol);
        List<DeepSeekSceneTimelineService.SceneRow> scenes = loadScenes(sceneBytes);
        requireSceneCoverage(scenes, beginDate, endDate);

        Field barsField = BacktestService.class.getDeclaredField("queryService");
        barsField.setAccessible(true);
        barsField.set(service, new LocalBars(symbol, bars));
        Field scenesField = BacktestService.class.getDeclaredField("sceneTimelineService");
        scenesField.setAccessible(true);
        scenesField.set(service, new LocalScenes(symbol, scenes));
        System.out.println("localResearchInput=" + inputDir.toAbsolutePath().normalize()
                + ", symbol=" + symbol + ", bars=" + bars.size()
                + ", scenes=" + scenes.size() + ", clock=UTC");
    }

    private static void requireHash(byte[] bytes, String expected, Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] actual = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(actual.length * 2);
        for (byte item : actual) {
            hex.append(String.format("%02x", item & 0xff));
        }
        if (expected == null || !hex.toString().equalsIgnoreCase(expected)) {
            throw new IllegalArgumentException("local research input hash mismatch: " + path);
        }
    }

    static List<TTbookOhlc> loadBars(byte[] bytes, String symbol) {
        List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
        LocalDateTime previous = null;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 6) {
                throw new IllegalArgumentException("local bar must have six fields");
            }
            LocalDateTime time = LocalDateTime.parse(fields[0], BAR_TIME);
            if (previous != null && !time.isAfter(previous)) {
                throw new IllegalArgumentException("local bars are duplicate or unordered");
            }
            previous = time;
            TTbookOhlc bar = new TTbookOhlc();
            bar.starttime = time.format(DB_TIME) + ".000";
            bar.endtime = time.plusMinutes(15).format(DB_TIME) + ".000";
            bar.tradedate = time.toLocalDate().toString();
            bar.fmttime = time.format(DB_TIME);
            bar.securityid = symbol;
            bar.text = "15m";
            bar.open = new BigDecimal(fields[1]);
            bar.high = new BigDecimal(fields[2]);
            bar.low = new BigDecimal(fields[3]);
            bar.close = new BigDecimal(fields[4]);
            bar.volume = new BigDecimal(fields[5]);
            bar.turnover = BigDecimal.ZERO;
            if (bar.low.compareTo(bar.high) > 0 || bar.volume.signum() < 0
                    || bar.open.signum() <= 0 || bar.close.signum() <= 0
                    || bar.open.compareTo(bar.low) < 0 || bar.open.compareTo(bar.high) > 0
                    || bar.close.compareTo(bar.low) < 0 || bar.close.compareTo(bar.high) > 0) {
                throw new IllegalArgumentException("invalid local OHLCV at " + fields[0]);
            }
            result.add(bar);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("local bar input is empty");
        return result;
    }

    static List<DeepSeekSceneTimelineService.SceneRow> loadScenes(byte[] bytes) {
        List<DeepSeekSceneTimelineService.SceneRow> result =
                new ArrayList<DeepSeekSceneTimelineService.SceneRow>();
        LocalDateTime previous = null;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 5 || !"SUCCESS".equals(fields[2])
                    || !"historical_kline_scene".equals(fields[3])
                    || !PROMPT_VERSION.equals(fields[4])) {
                throw new IllegalArgumentException("invalid local historical scene row");
            }
            LocalDateTime time = LocalDateTime.parse(fields[0], DB_TIME);
            if (previous != null && !time.isAfter(previous)) {
                throw new IllegalArgumentException("local scenes are duplicate or unordered");
            }
            previous = time;
            DeepSeekSceneTimelineService.SceneRow row = new DeepSeekSceneTimelineService.SceneRow();
            row.runTime = fields[0];
            row.scene = fields[1];
            row.status = fields[2];
            row.analysisType = fields[3];
            row.promptVersion = fields[4];
            result.add(row);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("local scene input is empty");
        return result;
    }

    static void requireSceneCoverage(List<DeepSeekSceneTimelineService.SceneRow> rows,
                                     String beginDate, String endDate) {
        LocalDate begin = LocalDate.parse(beginDate);
        LocalDate end = LocalDate.parse(endDate);
        if (begin.isAfter(end)) throw new IllegalArgumentException("invalid local scene dates");
        int index = 0;
        for (LocalDate day = begin; !day.isAfter(end); day = day.plusDays(1)) {
            for (int hour : new int[]{0, 12}) {
                String expected = day.atTime(hour, 0).format(DB_TIME);
                while (index < rows.size() && rows.get(index).runTime.compareTo(expected) < 0) index++;
                if (index >= rows.size() || !expected.equals(rows.get(index).runTime)) {
                    throw new IllegalArgumentException("local scene gap at " + expected);
                }
                index++;
            }
        }
    }

    private static final class LocalBars extends BacktestQueryService {
        private final String symbol;
        private final List<TTbookOhlc> bars;

        private LocalBars(String symbol, List<TTbookOhlc> bars) {
            this.symbol = symbol;
            this.bars = Collections.unmodifiableList(bars);
        }

        @Override
        public List<TTbookOhlc> queryOhlc(String querySymbol, String text,
                                         String beginDate, String endDate) {
            if (!symbol.equals(querySymbol) || !"15m".equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("unexpected local bar query scope");
            }
            List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
            for (TTbookOhlc bar : bars) {
                String date = bar.starttime.substring(0, 10);
                if (date.compareTo(beginDate) >= 0 && date.compareTo(endDate) <= 0) {
                    result.add(bar);
                }
            }
            return result;
        }

        @Override
        public int queryOhlcCount(String querySymbol, String text,
                                  String beginDate, String endDate) {
            return queryOhlc(querySymbol, text, beginDate, endDate).size();
        }
    }

    private static final class LocalScenes extends DeepSeekSceneTimelineService {
        private final String symbol;
        private final List<SceneRow> rows;

        private LocalScenes(String symbol, List<SceneRow> rows) {
            this.symbol = symbol;
            this.rows = Collections.unmodifiableList(rows);
        }

        @Override
        public Timeline load(String querySymbol, String beginDate, String endDate) {
            if (!symbol.equals(querySymbol)) {
                throw new IllegalArgumentException("unexpected local scene query scope");
            }
            requireSceneCoverage(rows, beginDate, endDate);
            String lower = LocalDate.parse(beginDate).minusDays(1) + " 00:00:00";
            String upper = LocalDate.parse(endDate) + " 23:59:59";
            List<SceneRow> selected = new ArrayList<SceneRow>();
            for (SceneRow row : rows) {
                if (row.runTime.compareTo(lower) >= 0 && row.runTime.compareTo(upper) <= 0) {
                    selected.add(row);
                }
            }
            return buildTimeline(selected, 13);
        }
    }
}
