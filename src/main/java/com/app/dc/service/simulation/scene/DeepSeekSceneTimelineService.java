package com.app.dc.service.simulation.scene;

import com.app.common.db.ClickHouseDBUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class DeepSeekSceneTimelineService {

    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long BUCKET_SECONDS = 12L * 60L * 60L;

    @Value("${strategy.backtest.sceneShadow.table:dc.deepseek_market_scene_analysis}")
    private String tableName;

    @Value("${strategy.backtest.sceneShadow.promptVersion:deepseek_market_scene_v3}")
    private String promptVersion;

    @Value("${strategy.backtest.sceneShadow.historyPromptVersion:market_scene_v3_historical_kline_v1}")
    private String historyPromptVersion;

    @Value("${strategy.backtest.sceneShadow.maxSceneAgeHours:13}")
    private int maxSceneAgeHours;

    public Timeline load(String symbol, String beginDate, String endDate) {
        if (StringUtils.isBlank(symbol) || StringUtils.isBlank(beginDate) || StringUtils.isBlank(endDate)) {
            return Timeline.empty(Math.max(1, maxSceneAgeHours));
        }
        String sql = "SELECT formatDateTime(run_time, '%Y-%m-%d %H:%i:%S') AS runTime,"
                + " scene, status, analysis_type AS analysisType, prompt_version AS promptVersion"
                + " FROM " + safeTableName(tableName)
                + " WHERE symbol=? AND run_time>=toDateTime(?) AND run_time<=toDateTime(?)"
                + " AND ((analysis_type='deepseek_market_scene' AND prompt_version=?)"
                + " OR (analysis_type='historical_kline_scene' AND prompt_version=?))"
                + " ORDER BY run_time ASC LIMIT 10000";
        String begin = LocalDateTime.parse(beginDate + " 00:00:00", DB_TIME)
                .minusDays(1).format(DB_TIME);
        String end = LocalDateTime.parse(endDate + " 23:59:59", DB_TIME).format(DB_TIME);
        try {
            List<SceneRow> rows = ClickHouseDBUtils.queryList(sql,
                    new Object[]{symbol.trim().toUpperCase(Locale.ROOT), begin, end,
                            promptVersion, historyPromptVersion},
                    SceneRow.class);
            return buildTimeline(rows, Math.max(1, maxSceneAgeHours));
        } catch (Exception e) {
            log.warn("scene shadow timeline query failed, symbol:{}, range:{}~{}", symbol, beginDate, endDate, e);
            return Timeline.error(Math.max(1, maxSceneAgeHours), e.getMessage());
        }
    }

    static Timeline buildTimeline(List<SceneRow> rows, int maxAgeHours) {
        if (rows == null || rows.isEmpty()) {
            return Timeline.empty(maxAgeHours);
        }
        Map<Long, ScenePoint> latestAny = new LinkedHashMap<Long, ScenePoint>();
        Map<Long, ScenePoint> latestSuccess = new LinkedHashMap<Long, ScenePoint>();
        for (SceneRow row : rows) {
            Instant time = parseTime(row == null ? null : row.runTime);
            if (time == null) {
                continue;
            }
            long bucket = Math.floorDiv(time.getEpochSecond(), BUCKET_SECONDS);
            ScenePoint point = new ScenePoint(time,
                    normalizeScene(row.scene),
                    StringUtils.defaultString(row.status).trim().toUpperCase(Locale.ROOT),
                    sourcePriority(row));
            putLatest(latestAny, bucket, point);
            if ("SUCCESS".equals(point.status)) {
                putLatest(latestSuccess, bucket, point);
            }
        }
        List<ScenePoint> points = new ArrayList<ScenePoint>();
        for (Map.Entry<Long, ScenePoint> entry : latestAny.entrySet()) {
            ScenePoint selected = latestSuccess.get(entry.getKey());
            if (selected == null) {
                ScenePoint failed = entry.getValue();
                selected = new ScenePoint(failed.time, "no_trade", failed.status, failed.sourcePriority);
            }
            points.add(selected);
        }
        Collections.sort(points, Comparator.comparing(point -> point.time));
        return new Timeline(points, maxAgeHours, "");
    }

    private static void putLatest(Map<Long, ScenePoint> target, long bucket, ScenePoint point) {
        ScenePoint current = target.get(bucket);
        if (current == null || current.sourcePriority < point.sourcePriority
                || (current.sourcePriority == point.sourcePriority && current.time.isBefore(point.time))) {
            target.put(bucket, point);
        }
    }

    private static int sourcePriority(SceneRow row) {
        if (row != null && "deepseek_market_scene".equalsIgnoreCase(row.analysisType)
                && "deepseek_market_scene_v3".equalsIgnoreCase(row.promptVersion)) {
            return 2;
        }
        if (row != null && "historical_kline_scene".equalsIgnoreCase(row.analysisType)) {
            return 1;
        }
        return 0;
    }

    private static String normalizeScene(String value) {
        String scene = StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
        return scene.isEmpty() ? "no_trade" : scene;
    }

    private static Instant parseTime(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DB_TIME).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignore) {
            try {
                return Instant.parse(value.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String safeTableName(String value) {
        String table = StringUtils.defaultIfBlank(value, "dc.deepseek_market_scene_analysis").trim();
        if (!table.matches("[A-Za-z0-9_$.]+")) {
            throw new IllegalArgumentException("invalid scene analysis table: " + table);
        }
        return table;
    }

    public static final class Timeline {
        private final List<ScenePoint> points;
        private final int maxAgeHours;
        private final String error;

        private Timeline(List<ScenePoint> points, int maxAgeHours, String error) {
            this.points = points == null ? Collections.<ScenePoint>emptyList() : points;
            this.maxAgeHours = Math.max(1, maxAgeHours);
            this.error = StringUtils.defaultString(error);
        }

        static Timeline empty(int maxAgeHours) {
            return new Timeline(Collections.<ScenePoint>emptyList(), maxAgeHours, "");
        }

        static Timeline error(int maxAgeHours, String error) {
            return new Timeline(Collections.<ScenePoint>emptyList(), maxAgeHours, error);
        }

        public Cursor cursor() {
            return new Cursor(points, maxAgeHours);
        }

        public int size() {
            return points.size();
        }

        public boolean hasError() {
            return StringUtils.isNotBlank(error);
        }

        public String error() {
            return error;
        }

        public Instant firstTime() {
            return points.isEmpty() ? null : points.get(0).time;
        }

        public Instant lastTime() {
            return points.isEmpty() ? null : points.get(points.size() - 1).time;
        }
    }

    public static final class Cursor {
        private final List<ScenePoint> points;
        private final int maxAgeHours;
        private int index = -1;
        private ScenePoint current;

        private Cursor(List<ScenePoint> points, int maxAgeHours) {
            this.points = points;
            this.maxAgeHours = maxAgeHours;
        }

        public ScenePoint at(Instant barTime) {
            if (barTime == null) {
                return null;
            }
            while (index + 1 < points.size() && !points.get(index + 1).time.isAfter(barTime)) {
                current = points.get(++index);
            }
            if (current == null || Duration.between(current.time, barTime).toHours() > maxAgeHours) {
                return null;
            }
            return current;
        }
    }

    public static final class ScenePoint {
        public final Instant time;
        public final String scene;
        public final String status;
        final int sourcePriority;

        ScenePoint(Instant time, String scene, String status, int sourcePriority) {
            this.time = time;
            this.scene = scene;
            this.status = status;
            this.sourcePriority = sourcePriority;
        }

        public boolean matches(String expectedScene) {
            return "SUCCESS".equals(status)
                    && scene.equals(normalizeScene(expectedScene))
                    && !"no_trade".equals(scene);
        }
    }

    public static class SceneRow {
        public String runTime;
        public String scene;
        public String status;
        public String analysisType;
        public String promptVersion;
    }
}
