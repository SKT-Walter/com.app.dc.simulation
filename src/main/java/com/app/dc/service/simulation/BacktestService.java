package com.app.dc.service.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels.BacktestResponse;
import com.app.dc.service.simulation.BacktestModels.BacktestResult;
import com.app.dc.service.simulation.BacktestModels.TradeRecord;
import com.app.dc.service.simulation.runtime.StrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import com.app.dc.service.simulation.runtime.WalkForwardBacktestRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class BacktestService {

    @Autowired
    private BacktestQueryService queryService;

    @Autowired
    private BacktestSupportService supportService;

    @Autowired
    private StrategyBacktestTaskDao strategyBacktestTaskDao;

    @Autowired
    private WalkForwardBacktestRunner walkForwardBacktestRunner;

    public BacktestResponse run(BacktestParam param) throws Exception {
        return run(param, 120, 30, 14);
    }

    public BacktestResponse run(BacktestParam param,
                                int fitWindowDays,
                                int validateWindowDays,
                                int forwardWindowDays) throws Exception {
        BacktestParam req = normalizeParam(param);
        if (req.strategyName == null || req.strategyName.trim().isEmpty()) {
            throw new IllegalArgumentException("strategyName is required");
        }
        if (req.strategyVersion == null || req.strategyVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("strategyVersion is required");
        }
        StrategyCandidateRow candidate = strategyBacktestTaskDao.loadCandidate(req.strategyName, req.strategyVersion);
        if (candidate == null) {
            throw new IllegalArgumentException("strategy candidate not found: " + req.strategyName + "@" + req.strategyVersion);
        }
        req.runtimeType = candidate.runtimeType;
        req.scene = candidate.scene;
        req.strategyPayload = candidate.payload;

        List<String> symbols = supportService.resolveSymbols(req.symbols, req.symbol);

        BacktestResponse response = new BacktestResponse();
        response.symbol = symbols.size() == 1 ? symbols.get(0) : "MULTI";
        response.symbols = symbols;
        response.text = req.text;
        response.beginDate = req.beginDate;
        response.endDate = req.endDate;
        response.strategyName = candidate.strategyName;
        response.strategyVersion = candidate.strategyVersion;
        response.baselineVersion = req.baselineVersion;
        response.runtimeType = candidate.runtimeType;
        response.scene = candidate.scene;
        response.windowMode = "WALK_FORWARD";

        List<BacktestResult> results = new ArrayList<BacktestResult>();
        BigDecimal fitPnl = BigDecimal.ZERO;
        BigDecimal validatePnl = BigDecimal.ZERO;
        BigDecimal forwardPnl = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        int sliceCount = Integer.MAX_VALUE;
        boolean overfitPass = true;
        String overfitReason = "";
        for (String symbol : symbols) {
            List<TTbookOhlc> ohlcList = queryService.queryOhlc(symbol, req.text, req.beginDate, req.endDate);
            BacktestParam symbolParam = copyParamForSymbol(req, symbol);
            BacktestResult result = walkForwardBacktestRunner.run(candidate, symbolParam, ohlcList,
                    fitWindowDays, validateWindowDays, forwardWindowDays);
            results.add(result);
            fitPnl = fitPnl.add(nz(result.fitPnl));
            validatePnl = validatePnl.add(nz(result.validatePnl));
            forwardPnl = forwardPnl.add(nz(result.forwardPnl));
            totalPnl = totalPnl.add(nz(result.totalPnl));
            sliceCount = Math.min(sliceCount, result.sliceCount == null ? 0 : result.sliceCount.intValue());
            if (!Integer.valueOf(1).equals(result.overfitPass)) {
                overfitPass = false;
                if (overfitReason.isEmpty() && result.overfitReason != null) {
                    overfitReason = result.overfitReason;
                }
            }
        }
        response.results = results.isEmpty() ? Collections.<BacktestResult>emptyList() : results;
        response.fitPnl = scale(fitPnl.doubleValue());
        response.validatePnl = scale(validatePnl.doubleValue());
        response.forwardPnl = scale(forwardPnl.doubleValue());
        response.totalPnl = scale(totalPnl.doubleValue());
        response.sliceCount = results.isEmpty() ? 0 : sliceCount;
        response.overfitPass = results.isEmpty() ? 0 : (overfitPass ? 1 : 0);
        response.overfitReason = overfitReason;
        return response;
    }

    private BacktestParam copyParamForSymbol(BacktestParam source, String symbol) {
        BacktestParam target = new BacktestParam();
        target.strategyName = source.strategyName;
        target.strategyVersion = source.strategyVersion;
        target.baselineVersion = source.baselineVersion;
        target.runtimeType = source.runtimeType;
        target.scene = source.scene;
        target.strategyPayload = source.strategyPayload;
        target.symbol = symbol;
        target.symbols = symbol;
        target.text = source.text;
        target.beginDate = source.beginDate;
        target.endDate = source.endDate;
        target.initialCapital = source.initialCapital;
        target.feeRatePct = source.feeRatePct;
        target.entryMakerFeeRatePct = source.entryMakerFeeRatePct;
        target.exitTakerFeeRatePct = source.exitTakerFeeRatePct;
        target.fallbackStopLossPct = source.fallbackStopLossPct;
        target.fallbackTakeProfitPct = source.fallbackTakeProfitPct;
        target.maxHoldBars = source.maxHoldBars;
        target.ignoreSentimentGuard = source.ignoreSentimentGuard;
        target.allowMissingStageAnalysis = source.allowMissingStageAnalysis;
        return target;
    }

    public BacktestParam normalizeParam(BacktestParam param) {
        BacktestParam req = param == null ? new BacktestParam() : param;
        if (req.symbol == null || req.symbol.trim().isEmpty()) {
            req.symbol = "ETHUSDT";
        }
        if (req.symbols == null || req.symbols.trim().isEmpty()) {
            req.symbols = req.symbol;
        }
        if (req.text == null || req.text.trim().isEmpty()) {
            req.text = "15m";
        }
        req.text = supportService.normalizeText(req.text);
        if (req.initialCapital == null || req.initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            req.initialCapital = BigDecimal.valueOf(10000);
        }
        if (req.feeRatePct == null || req.feeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.feeRatePct = BigDecimal.ZERO;
        }
        if (req.entryMakerFeeRatePct == null || req.entryMakerFeeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.entryMakerFeeRatePct = req.feeRatePct.compareTo(BigDecimal.ZERO) > 0
                    ? req.feeRatePct
                    : new BigDecimal("0.02");
        }
        if (req.exitTakerFeeRatePct == null || req.exitTakerFeeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.exitTakerFeeRatePct = req.feeRatePct.compareTo(BigDecimal.ZERO) > 0
                    ? req.feeRatePct
                    : new BigDecimal("0.05");
        }
        if (req.fallbackStopLossPct == null || req.fallbackStopLossPct.compareTo(BigDecimal.ZERO) < 0) {
            req.fallbackStopLossPct = BigDecimal.ZERO;
        }
        if (req.fallbackTakeProfitPct == null || req.fallbackTakeProfitPct.compareTo(BigDecimal.ZERO) < 0) {
            req.fallbackTakeProfitPct = BigDecimal.ZERO;
        }
        if (req.maxHoldBars == null || req.maxHoldBars < 0) {
            req.maxHoldBars = 0;
        }
        if (req.ignoreSentimentGuard == null) {
            req.ignoreSentimentGuard = true;
        }
        if (req.allowMissingStageAnalysis == null) {
            req.allowMissingStageAnalysis = true;
        }
        if (req.runtimeType == null || req.runtimeType.trim().isEmpty()) {
            req.runtimeType = "JAR";
        }
        return req;
    }

    public BacktestResult initResult(String strategyName, BacktestParam param) {
        BacktestResult result = new BacktestResult();
        result.strategyName = strategyName;
        result.strategyVersion = param.strategyVersion;
        result.baselineVersion = param.baselineVersion;
        result.runtimeType = param.runtimeType;
        result.scene = param.scene;
        result.strategyPayload = param.strategyPayload;
        result.symbol = param.symbol;
        result.text = param.text;
        result.beginDate = param.beginDate;
        result.endDate = param.endDate;
        result.initialCapital = scale(param.initialCapital.doubleValue());
        result.finalCapital = scale(param.initialCapital.doubleValue());
        result.feeRatePct = scale(param.entryMakerFeeRatePct.doubleValue() + param.exitTakerFeeRatePct.doubleValue());
        result.entryMakerFeeRatePct = scale(param.entryMakerFeeRatePct.doubleValue());
        result.exitTakerFeeRatePct = scale(param.exitTakerFeeRatePct.doubleValue());
        result.fallbackStopLossPct = scale(param.fallbackStopLossPct.doubleValue());
        result.fallbackTakeProfitPct = scale(param.fallbackTakeProfitPct.doubleValue());
        result.maxHoldBars = param.maxHoldBars;
        result.tradeList = new ArrayList<TradeRecord>();
        result.equityCurve = new ArrayList<BacktestModels.EquityPoint>();
        result.rejectReasonCounts = new java.util.LinkedHashMap<String, Integer>();
        return result;
    }

    public Bar toBar(TTbookOhlc ohlc, Duration duration) throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        ZonedDateTime time = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(sdf.parse(ohlc.starttime).getTime()), ZoneId.systemDefault());
        return new BaseBar(duration, time, ohlc.open, ohlc.high, ohlc.low, ohlc.close, ohlc.volume);
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
