package com.app.dc.service.simulation.scene;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestQueryService;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.VersionedBacktestRunner;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class SceneGatedShadowBacktestService {

    private static final Set<String> SUPPORTED_SCENES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("trend", "range", "channel")));

    @Autowired
    private DeepSeekSceneTimelineService sceneTimelineService;

    @Autowired
    private BacktestQueryService backtestQueryService;

    @Autowired
    private VersionedBacktestRunner versionedBacktestRunner;

    @Value("${strategy.backtest.sceneShadow.enabled:true}")
    private boolean enabled;

    @Value("${strategy.backtest.sceneShadow.warmupDays:7}")
    private int warmupDays;

    @Value("${strategy.backtest.sceneShadow.minSceneRecords:6}")
    private int minSceneRecords;

    @Value("${strategy.backtest.sceneShadow.minTrades:5}")
    private int minTrades;

    public void enrich(BacktestModels.BacktestResponse response,
                       StrategyCandidateRow candidate,
                       BacktestParam baseParam) {
        if (!enabled || response == null || candidate == null || response.results == null) {
            return;
        }
        for (BacktestModels.BacktestResult result : response.results) {
            if (result == null) {
                continue;
            }
            try {
                result.sceneShadow = runOne(candidate, baseParam, result);
            } catch (Exception e) {
                BacktestModels.SceneShadowMetrics metrics = baseMetrics(candidate.scene);
                metrics.status = "ERROR";
                metrics.message = "scene shadow replay failed: " + StringUtils.defaultString(e.getMessage());
                result.sceneShadow = metrics;
                log.warn("scene shadow replay failed, strategy:{}@{}, symbol:{}",
                        candidate.strategyName, candidate.strategyVersion, result.symbol, e);
            }
        }
    }

    private BacktestModels.SceneShadowMetrics runOne(StrategyCandidateRow candidate,
                                                      BacktestParam baseParam,
                                                      BacktestModels.BacktestResult fullResult) throws Exception {
        BacktestModels.SceneShadowMetrics metrics = baseMetrics(candidate.scene);
        String expectedScene = normalizeScene(candidate.scene);
        if (!SUPPORTED_SCENES.contains(expectedScene)) {
            metrics.status = "NOT_APPLICABLE";
            metrics.message = "Historical scene sample is not mature for " + expectedScene;
            return metrics;
        }

        DeepSeekSceneTimelineService.Timeline timeline = sceneTimelineService.load(
                fullResult.symbol, fullResult.beginDate, fullResult.endDate);
        if (timeline.hasError()) {
            metrics.status = "ERROR";
            metrics.message = timeline.error();
            return metrics;
        }
        metrics.sceneRecordCount = timeline.size();
        metrics.dataBegin = timeline.firstTime() == null ? "" : timeline.firstTime().toString();
        metrics.dataEnd = timeline.lastTime() == null ? "" : timeline.lastTime().toString();
        if (timeline.size() == 0) {
            metrics.message = "No comparable v3 scene history is available";
            return metrics;
        }

        List<TTbookOhlc> rows = backtestQueryService.queryOhlc(
                fullResult.symbol, fullResult.text, fullResult.beginDate, fullResult.endDate);
        rows = trimWarmup(rows, timeline.firstTime(), Math.max(1, warmupDays));
        if (rows.isEmpty()) {
            metrics.message = "No K-line data overlaps the scene history";
            return metrics;
        }

        BacktestParam param = copyParam(baseParam);
        param.symbol = fullResult.symbol;
        param.symbols = fullResult.symbol;
        param.text = fullResult.text;
        param.beginDate = fullResult.beginDate;
        param.endDate = fullResult.endDate;
        param.strategyName = candidate.strategyName;
        param.strategyVersion = candidate.strategyVersion;
        param.runtimeType = candidate.runtimeType;
        param.scene = candidate.scene;
        param.strategyPayload = candidate.payload;
        if (param.strategyParams == null) {
            param.strategyParams = new LinkedHashMap<String, Object>();
        }
        param.strategyParams.putAll(parseParamSet(fullResult.bestParamSetJson));

        BacktestModels.BacktestResult shadow = versionedBacktestRunner.runSceneGated(
                candidate, param, rows, timeline.cursor(), metrics);
        metrics.tradeCount = nzInt(shadow.tradeCount);
        metrics.totalPnl = nz(shadow.totalPnl);
        metrics.totalFee = nz(shadow.totalFee);
        metrics.maxDrawdownPct = nz(shadow.maxDrawdownPct);
        metrics.profitFactor = nz(shadow.profitFactor);
        metrics.winRate = nz(shadow.winRate);
        if (metrics.sceneRecordCount < Math.max(1, minSceneRecords)) {
            metrics.status = "INSUFFICIENT_DATA";
            metrics.message = "Scene history is too short for a stable conclusion";
        } else if (metrics.matchedBarCount <= 0) {
            metrics.status = "INSUFFICIENT_DATA";
            metrics.message = "No K-line interval matched the strategy scene";
        } else if (metrics.tradeCount < Math.max(1, minTrades)) {
            metrics.status = "INSUFFICIENT_DATA";
            metrics.message = "Scene-matched trades are too few; keep observing";
        } else {
            metrics.status = "OBSERVATION_ONLY";
            metrics.message = "Scene-conditioned result is for observation and does not affect live publishing";
        }
        return metrics;
    }

    private List<TTbookOhlc> trimWarmup(List<TTbookOhlc> rows, Instant firstSceneTime, int days) {
        if (rows == null || rows.isEmpty() || firstSceneTime == null) {
            return rows == null ? Collections.<TTbookOhlc>emptyList() : rows;
        }
        Instant threshold = firstSceneTime.minus(days, ChronoUnit.DAYS);
        List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        for (TTbookOhlc row : rows) {
            try {
                if (row != null && row.starttime != null
                        && !format.parse(row.starttime).toInstant().isBefore(threshold)) {
                    result.add(row);
                }
            } catch (Exception ignore) {
                result.add(row);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParamSet(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = JsonUtils.Deserialize(json, Map.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Collections.<String, Object>emptyMap();
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

    private BacktestParam copyParam(BacktestParam source) {
        if (source == null) {
            return new BacktestParam();
        }
        try {
            return JsonUtils.Deserialize(JsonUtils.Serializer(source), BacktestParam.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot copy backtest parameters", e);
        }
    }

    private BacktestModels.SceneShadowMetrics baseMetrics(String scene) {
        BacktestModels.SceneShadowMetrics metrics = new BacktestModels.SceneShadowMetrics();
        metrics.strategyScene = normalizeScene(scene);
        return metrics;
    }

    private String normalizeScene(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    private int nzInt(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
