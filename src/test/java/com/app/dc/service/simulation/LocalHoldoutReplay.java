package com.app.dc.service.simulation;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.runtime.ExecutionStressService;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.VersionedBacktestRunner;
import com.app.dc.service.simulation.scene.DeepSeekSceneTimelineService;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only chronological holdout using the same scene-gated production execution engine. */
final class LocalHoldoutReplay {
    private LocalHoldoutReplay() { }

    static Map<String, Object> run(ConfigurableApplicationContext context, BacktestService service,
                                   BacktestParam param, String begin) throws Exception {
        if (LocalDate.parse(begin).isBefore(LocalDate.parse(param.beginDate))
                || LocalDate.parse(begin).isAfter(LocalDate.parse(param.endDate))) {
            throw new IllegalArgumentException("holdout dates are outside the frozen input range");
        }
        BacktestQueryService bars = (BacktestQueryService) field(service, "queryService");
        DeepSeekSceneTimelineService scenes =
                (DeepSeekSceneTimelineService) field(service, "sceneTimelineService");
        StrategyBacktestTaskDao dao = (StrategyBacktestTaskDao) field(service, "strategyBacktestTaskDao");
        StrategyCandidateRow candidate = dao.loadCandidate(param.strategyName, param.strategyVersion);
        param.beginDate = begin;
        BacktestModels.BacktestResult result = context.getBean(VersionedBacktestRunner.class)
                .runSceneGated(candidate, param,
                        bars.queryOhlc(param.symbol, param.text, begin, param.endDate),
                        scenes.load(param.symbol, begin, param.endDate).cursor(),
                        new BacktestModels.SceneShadowMetrics());
        BigDecimal adjusted = context.getBean(ExecutionStressService.class).adjustedPnl(result);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("beginDate", begin);
        summary.put("endDate", param.endDate);
        summary.put("tradeCount", result.tradeCount);
        summary.put("profitFactor", result.profitFactor);
        summary.put("maxDrawdownPct", result.maxDrawdownPct);
        summary.put("feeAdjustedPnl", adjusted);
        summary.put("passed", passes(result, adjusted));
        summary.put("trades", result.tradeList);
        summary.put("rejectReasonCounts", result.rejectReasonCounts);
        return summary;
    }

    static boolean passes(BacktestModels.BacktestResult result, BigDecimal adjusted) {
        return result != null && result.tradeCount != null && result.tradeCount >= 3
                && result.profitFactor != null && result.profitFactor.compareTo(new BigDecimal("1.20")) >= 0
                && result.maxDrawdownPct != null && result.maxDrawdownPct.compareTo(new BigDecimal("0.15")) <= 0
                && adjusted != null && adjusted.signum() > 0;
    }

    private static Object field(BacktestService service, String name) throws Exception {
        Field field = BacktestService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }
}
