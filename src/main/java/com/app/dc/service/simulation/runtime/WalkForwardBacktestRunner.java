package com.app.dc.service.simulation.runtime;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestSupportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class WalkForwardBacktestRunner {

    private static final String WINDOW_MODE = "WALK_FORWARD";

    @Autowired
    private VersionedBacktestRunner versionedBacktestRunner;

    @Autowired
    private BacktestSupportService supportService;

    public BacktestModels.BacktestResult run(StrategyCandidateRow candidate,
                                             BacktestParam rawParam,
                                             List<TTbookOhlc> ohlcList,
                                             int fitWindowDays,
                                             int validateWindowDays,
                                             int forwardWindowDays,
                                             int minSliceCount) throws Exception {
        BacktestParam param = rawParam == null ? new BacktestParam() : rawParam;
        List<TTbookOhlc> rows = ohlcList == null ? new ArrayList<TTbookOhlc>() : ohlcList;
        LocalDate beginDate = LocalDate.parse(param.beginDate);
        LocalDate endDate = LocalDate.parse(param.endDate);
        List<WindowSlice> slices = buildSlices(beginDate, endDate, fitWindowDays, validateWindowDays, forwardWindowDays);
        log.info("WalkForwardBacktestRunner start, strategy:{}@{}, symbol:{}, text:{}, bars:{}, range:{}~{}, window:{}/{}/{}, minSliceCount:{}, builtSlices:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                param.symbol,
                param.text,
                rows.size(),
                beginDate,
                endDate,
                fitWindowDays,
                validateWindowDays,
                forwardWindowDays,
                minSliceCount,
                slices.size());
        if (slices.size() < Math.max(1, minSliceCount)) {
            throw insufficient(candidate, param, fitWindowDays, validateWindowDays, forwardWindowDays, rows, beginDate, endDate,
                    "window slices less than " + Math.max(1, minSliceCount));
        }

        BacktestModels.BacktestResult aggregate = initAggregateResult(candidate, param);
        aggregate.fitWindowDays = fitWindowDays;
        aggregate.validateWindowDays = validateWindowDays;
        aggregate.forwardWindowDays = forwardWindowDays;
        aggregate.minSliceCount = Math.max(1, minSliceCount);
        BigDecimal fitPnl = BigDecimal.ZERO;
        BigDecimal validatePnl = BigDecimal.ZERO;
        BigDecimal forwardPnl = BigDecimal.ZERO;
        BigDecimal forwardScoreSum = BigDecimal.ZERO;

        int barsPerDay = resolveBarsPerDay(param.text);
        int sliceNo = 0;
        for (WindowSlice slice : slices) {
            sliceNo++;
            List<TTbookOhlc> fitRows = filterRows(rows, slice.fitBegin, slice.fitEnd);
            List<TTbookOhlc> validateRows = filterRows(rows, slice.validateBegin, slice.validateEnd);
            List<TTbookOhlc> forwardRows = filterRows(rows, slice.forwardBegin, slice.forwardEnd);
            log.info("WalkForwardBacktestRunner slice start, strategy:{}@{}, symbol:{}, sliceNo:{}, fit:{}~{} bars:{}, validate:{}~{} bars:{}, forward:{}~{} bars:{}",
                    candidate.strategyName,
                    candidate.strategyVersion,
                    param.symbol,
                    sliceNo,
                    slice.fitBegin,
                    slice.fitEnd,
                    fitRows.size(),
                    slice.validateBegin,
                    slice.validateEnd,
                    validateRows.size(),
                    slice.forwardBegin,
                    slice.forwardEnd,
                    forwardRows.size());

            ensureEnoughBars(candidate, param, rows, slice.fitBegin, slice.fitEnd, fitRows.size(),
                    fitWindowDays * barsPerDay, "fit");
            ensureEnoughBars(candidate, param, rows, slice.validateBegin, slice.validateEnd, validateRows.size(),
                    validateWindowDays * barsPerDay, "validate");
            ensureEnoughBars(candidate, param, rows, slice.forwardBegin, slice.forwardEnd, forwardRows.size(),
                    forwardWindowDays * barsPerDay, "forward");

            BacktestModels.BacktestResult fitResult = versionedBacktestRunner.run(candidate,
                    copyParam(param, slice.fitBegin, slice.fitEnd), fitRows);
            BacktestModels.BacktestResult validateResult = versionedBacktestRunner.run(candidate,
                    copyParam(param, slice.validateBegin, slice.validateEnd), validateRows);
            BacktestModels.BacktestResult forwardResult = versionedBacktestRunner.run(candidate,
                    copyParam(param, slice.forwardBegin, slice.forwardEnd), forwardRows);

            fitPnl = fitPnl.add(nz(calcTotalPnl(fitResult)));
            validatePnl = validatePnl.add(nz(calcTotalPnl(validateResult)));
            forwardPnl = forwardPnl.add(nz(calcTotalPnl(forwardResult)));
            forwardScoreSum = forwardScoreSum.add(nz(forwardResult.totalReturnPct));

            aggregate.sliceResults.add(buildSlice(candidate, param, sliceNo, slice, fitResult, validateResult, forwardResult));
            mergePhase(aggregate, validateResult);
            mergePhase(aggregate, forwardResult);
            aggregate.maxDrawdownPct = max(aggregate.maxDrawdownPct, max(validateResult.maxDrawdownPct, forwardResult.maxDrawdownPct));
            aggregate.totalBars += nz(validateResult.totalBars) + nz(forwardResult.totalBars);
            log.info("WalkForwardBacktestRunner slice end, strategy:{}@{}, symbol:{}, sliceNo:{}, fitPnl:{}, validatePnl:{}, forwardPnl:{}, validateTrades:{}, forwardTrades:{}",
                    candidate.strategyName,
                    candidate.strategyVersion,
                    param.symbol,
                    sliceNo,
                    calcTotalPnl(fitResult),
                    calcTotalPnl(validateResult),
                    calcTotalPnl(forwardResult),
                    validateResult.tradeCount,
                    forwardResult.tradeCount);
        }

        aggregate.sliceCount = slices.size();
        aggregate.fitPnl = scale(fitPnl);
        aggregate.validatePnl = scale(validatePnl);
        aggregate.forwardPnl = scale(forwardPnl);
        aggregate.totalPnl = scale(validatePnl.add(forwardPnl));
        aggregate.windowMode = WINDOW_MODE;
        aggregate.forwardScore = slices.isEmpty()
                ? BigDecimal.ZERO
                : scale(forwardScoreSum.divide(BigDecimal.valueOf(slices.size()), 6, RoundingMode.HALF_UP));
        aggregate.initialCapital = scale(nz(param.initialCapital));
        aggregate.finalCapital = scale(nz(param.initialCapital).add(aggregate.totalPnl));
        aggregate.totalReturnPct = aggregate.initialCapital.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : scale(aggregate.totalPnl.divide(aggregate.initialCapital, 6, RoundingMode.HALF_UP));
        aggregate.winRate = aggregate.tradeCount == null || aggregate.tradeCount.intValue() <= 0
                ? BigDecimal.ZERO
                : scale(BigDecimal.valueOf(aggregate.winCount)
                .divide(BigDecimal.valueOf(aggregate.tradeCount), 6, RoundingMode.HALF_UP));
        aggregate.avgHoldBars = aggregate.tradeCount == null || aggregate.tradeCount.intValue() <= 0
                ? BigDecimal.ZERO
                : scale(BigDecimal.valueOf(sumHoldBars(aggregate.tradeList))
                .divide(BigDecimal.valueOf(aggregate.tradeCount), 6, RoundingMode.HALF_UP));
        aggregate.avgReturnPct = aggregate.tradeCount == null || aggregate.tradeCount.intValue() <= 0
                ? BigDecimal.ZERO
                : scale(sumReturns(aggregate.tradeList)
                .divide(BigDecimal.valueOf(aggregate.tradeCount), 6, RoundingMode.HALF_UP));
        aggregate.profitFactor = calcProfitFactor(aggregate.tradeList);

        if (aggregate.sliceCount.intValue() < Math.max(1, minSliceCount)) {
            aggregate.overfitPass = 0;
            aggregate.overfitReason = "slice_count < " + Math.max(1, minSliceCount);
        } else if (aggregate.fitPnl.compareTo(BigDecimal.ZERO) > 0
                && aggregate.validatePnl.compareTo(BigDecimal.ZERO) <= 0) {
            aggregate.overfitPass = 0;
            aggregate.overfitReason = "fit_pnl > 0 but validate_pnl <= 0";
        } else if (aggregate.validatePnl.compareTo(BigDecimal.ZERO) > 0
                && aggregate.forwardPnl.compareTo(BigDecimal.ZERO) <= 0) {
            aggregate.overfitPass = 0;
            aggregate.overfitReason = "validate_pnl > 0 but forward_pnl <= 0";
        } else {
            aggregate.overfitPass = 1;
            aggregate.overfitReason = "";
        }
        log.info("WalkForwardBacktestRunner end, strategy:{}@{}, symbol:{}, sliceCount:{}, fitPnl:{}, validatePnl:{}, forwardPnl:{}, totalPnl:{}, overfitPass:{}, overfitReason:{}",
                candidate.strategyName,
                candidate.strategyVersion,
                param.symbol,
                aggregate.sliceCount,
                aggregate.fitPnl,
                aggregate.validatePnl,
                aggregate.forwardPnl,
                aggregate.totalPnl,
                aggregate.overfitPass,
                aggregate.overfitReason);

        return aggregate;
    }

    private BacktestModels.BacktestResult initAggregateResult(StrategyCandidateRow candidate, BacktestParam param) {
        BacktestModels.BacktestResult result = new BacktestModels.BacktestResult();
        result.strategyName = candidate.strategyName;
        result.strategyVersion = candidate.strategyVersion;
        result.baselineVersion = param.baselineVersion;
        result.runtimeType = candidate.runtimeType;
        result.scene = candidate.scene;
        result.strategyPayload = candidate.payload;
        result.symbol = param.symbol;
        result.text = param.text;
        result.beginDate = param.beginDate;
        result.endDate = param.endDate;
        result.initialCapital = scale(nz(param.initialCapital));
        result.finalCapital = result.initialCapital;
        result.entryMakerFeeRatePct = scale(nz(param.entryMakerFeeRatePct));
        result.exitTakerFeeRatePct = scale(nz(param.exitTakerFeeRatePct));
        result.windowMode = WINDOW_MODE;
        result.tradeList = new ArrayList<BacktestModels.TradeRecord>();
        result.equityCurve = new ArrayList<BacktestModels.EquityPoint>();
        result.sliceResults = new ArrayList<BacktestModels.BacktestSliceResult>();
        result.rejectReasonCounts = new LinkedHashMap<String, Integer>();
        return result;
    }

    private void mergePhase(BacktestModels.BacktestResult target, BacktestModels.BacktestResult phase) {
        if (phase == null) {
            return;
        }
        target.tradeCount += nz(phase.tradeCount);
        target.winCount += nz(phase.winCount);
        target.lossCount += nz(phase.lossCount);
        target.flatCount += nz(phase.flatCount);
        target.stopExitCount += nz(phase.stopExitCount);
        target.takeExitCount += nz(phase.takeExitCount);
        target.stopExitWinCount += nz(phase.stopExitWinCount);
        target.stopExitLossCount += nz(phase.stopExitLossCount);
        target.takeExitWinCount += nz(phase.takeExitWinCount);
        target.takeExitLossCount += nz(phase.takeExitLossCount);
        target.entryFeeTotal = add(target.entryFeeTotal, phase.entryFeeTotal);
        target.exitFeeTotal = add(target.exitFeeTotal, phase.exitFeeTotal);
        target.totalFee = add(target.totalFee, phase.totalFee);
        if (phase.tradeList != null && !phase.tradeList.isEmpty()) {
            target.tradeList.addAll(phase.tradeList);
        }
        mergeRejectReasons(target.rejectReasonCounts, phase.rejectReasonCounts);
    }

    private void mergeRejectReasons(Map<String, Integer> target, Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        Map<String, Integer> out = target == null ? new LinkedHashMap<String, Integer>() : target;
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            Integer prev = out.get(entry.getKey());
            out.put(entry.getKey(), Integer.valueOf((prev == null ? 0 : prev.intValue()) + nz(entry.getValue())));
        }
    }

    private BacktestModels.BacktestSliceResult buildSlice(StrategyCandidateRow candidate,
                                                          BacktestParam param,
                                                          int sliceNo,
                                                          WindowSlice slice,
                                                          BacktestModels.BacktestResult fitResult,
                                                          BacktestModels.BacktestResult validateResult,
                                                          BacktestModels.BacktestResult forwardResult) {
        BacktestModels.BacktestSliceResult row = new BacktestModels.BacktestSliceResult();
        row.strategyName = candidate.strategyName;
        row.strategyVersion = candidate.strategyVersion;
        row.symbol = param.symbol;
        row.text = param.text;
        row.sliceNo = sliceNo;
        row.fitBegin = slice.fitBegin.toString();
        row.fitEnd = slice.fitEnd.toString();
        row.validateBegin = slice.validateBegin.toString();
        row.validateEnd = slice.validateEnd.toString();
        row.forwardBegin = slice.forwardBegin.toString();
        row.forwardEnd = slice.forwardEnd.toString();
        row.fitPnl = scale(calcTotalPnl(fitResult));
        row.validatePnl = scale(calcTotalPnl(validateResult));
        row.forwardPnl = scale(calcTotalPnl(forwardResult));
        row.fitTradeCount = nz(fitResult.tradeCount);
        row.validateTradeCount = nz(validateResult.tradeCount);
        row.forwardTradeCount = nz(forwardResult.tradeCount);
        row.fitMaxDrawdownPct = scale(nz(fitResult.maxDrawdownPct));
        row.validateMaxDrawdownPct = scale(nz(validateResult.maxDrawdownPct));
        row.forwardMaxDrawdownPct = scale(nz(forwardResult.maxDrawdownPct));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("fitReturnPct", fitResult.totalReturnPct);
        payload.put("validateReturnPct", validateResult.totalReturnPct);
        payload.put("forwardReturnPct", forwardResult.totalReturnPct);
        payload.put("forwardScore", forwardResult.totalReturnPct);
        payload.put("fitWindowDays", fitWindowDays(param, slice));
        payload.put("validateWindowDays", validateWindowDays(param, slice));
        payload.put("forwardWindowDays", forwardWindowDays(param, slice));
        row.payload = com.gateway.connector.utils.JsonUtils.Serializer(payload);
        return row;
    }

    private void ensureEnoughBars(StrategyCandidateRow candidate,
                                  BacktestParam param,
                                  List<TTbookOhlc> rows,
                                  LocalDate begin,
                                  LocalDate end,
                                  int actualBars,
                                  int requiredBars,
                                  String segment) {
        if (actualBars >= requiredBars) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("reason", "INSUFFICIENT_KLINE");
        detail.put("segment", segment);
        detail.put("strategyName", candidate.strategyName);
        detail.put("strategyVersion", candidate.strategyVersion);
        detail.put("symbol", param.symbol);
        detail.put("text", param.text);
        detail.put("requiredBeginDate", begin.toString());
        detail.put("requiredEndDate", end.toString());
        detail.put("requiredBars", requiredBars);
        detail.put("actualBars", actualBars);
        detail.put("missingBars", Math.max(0, requiredBars - actualBars));
        detail.put("availableBeginDate", rows.isEmpty() ? "" : tradeDate(rows.get(0)));
        detail.put("availableEndDate", rows.isEmpty() ? "" : tradeDate(rows.get(rows.size() - 1)));
        detail.put("resumeHint", "run BinanceKlineImportCli then wait for retry");
        throw new BacktestTaskSuspendedException("INSUFFICIENT_KLINE", detail);
    }

    private BacktestTaskSuspendedException insufficient(StrategyCandidateRow candidate,
                                                        BacktestParam param,
                                                        int fitWindowDays,
                                                        int validateWindowDays,
                                                        int forwardWindowDays,
                                                        List<TTbookOhlc> rows,
                                                        LocalDate beginDate,
                                                        LocalDate endDate,
                                                        String message) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("reason", "INSUFFICIENT_KLINE");
        detail.put("strategyName", candidate.strategyName);
        detail.put("strategyVersion", candidate.strategyVersion);
        detail.put("symbol", param.symbol);
        detail.put("text", param.text);
        detail.put("requiredBeginDate", beginDate.toString());
        detail.put("requiredEndDate", endDate.toString());
        detail.put("fitWindowDays", fitWindowDays);
        detail.put("validateWindowDays", validateWindowDays);
        detail.put("forwardWindowDays", forwardWindowDays);
        detail.put("actualBars", rows == null ? 0 : rows.size());
        detail.put("resumeHint", "run BinanceKlineImportCli then wait for retry");
        detail.put("message", message);
        return new BacktestTaskSuspendedException("INSUFFICIENT_KLINE", detail);
    }

    private long fitWindowDays(BacktestParam param, WindowSlice slice) {
        return java.time.temporal.ChronoUnit.DAYS.between(slice.fitBegin, slice.fitEnd) + 1L;
    }

    private long validateWindowDays(BacktestParam param, WindowSlice slice) {
        return java.time.temporal.ChronoUnit.DAYS.between(slice.validateBegin, slice.validateEnd) + 1L;
    }

    private long forwardWindowDays(BacktestParam param, WindowSlice slice) {
        return java.time.temporal.ChronoUnit.DAYS.between(slice.forwardBegin, slice.forwardEnd) + 1L;
    }

    private List<WindowSlice> buildSlices(LocalDate beginDate, LocalDate endDate,
                                          int fitWindowDays, int validateWindowDays, int forwardWindowDays) {
        List<WindowSlice> slices = new ArrayList<WindowSlice>();
        if (beginDate == null || endDate == null || endDate.isBefore(beginDate)
                || fitWindowDays <= 0 || validateWindowDays <= 0 || forwardWindowDays <= 0) {
            return slices;
        }
        LocalDate cursor = beginDate;
        while (true) {
            WindowSlice slice = new WindowSlice();
            slice.fitBegin = cursor;
            slice.fitEnd = cursor.plusDays(fitWindowDays - 1L);
            slice.validateBegin = slice.fitEnd.plusDays(1L);
            slice.validateEnd = slice.validateBegin.plusDays(validateWindowDays - 1L);
            slice.forwardBegin = slice.validateEnd.plusDays(1L);
            slice.forwardEnd = slice.forwardBegin.plusDays(forwardWindowDays - 1L);
            if (slice.forwardEnd.isAfter(endDate)) {
                break;
            }
            slices.add(slice);
            cursor = cursor.plusDays(forwardWindowDays);
        }
        return slices;
    }

    private List<TTbookOhlc> filterRows(List<TTbookOhlc> rows, LocalDate begin, LocalDate end) {
        List<TTbookOhlc> result = new ArrayList<TTbookOhlc>();
        if (rows == null || rows.isEmpty()) {
            return result;
        }
        for (TTbookOhlc row : rows) {
            LocalDate tradeDate = LocalDate.parse(tradeDate(row));
            if ((tradeDate.isEqual(begin) || tradeDate.isAfter(begin))
                    && (tradeDate.isEqual(end) || tradeDate.isBefore(end))) {
                result.add(row);
            }
        }
        return result;
    }

    private BacktestParam copyParam(BacktestParam source, LocalDate begin, LocalDate end) {
        BacktestParam target = new BacktestParam();
        target.strategyName = source.strategyName;
        target.strategyVersion = source.strategyVersion;
        target.baselineVersion = source.baselineVersion;
        target.runtimeType = source.runtimeType;
        target.scene = source.scene;
        target.strategyPayload = source.strategyPayload;
        target.strategyParams = new LinkedHashMap<String, Object>();
        if (source.strategyParams != null && !source.strategyParams.isEmpty()) {
            target.strategyParams.putAll(source.strategyParams);
        }
        target.symbol = source.symbol;
        target.symbols = source.symbols;
        target.text = supportService.normalizeText(source.text);
        target.beginDate = begin.toString();
        target.endDate = end.toString();
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

    private int resolveBarsPerDay(String text) {
        String value = supportService.normalizeText(text);
        if ("1M".equals(value)) {
            return 24 * 60;
        }
        if ("5M".equals(value)) {
            return 24 * 12;
        }
        if ("15M".equals(value)) {
            return 24 * 4;
        }
        if ("30M".equals(value)) {
            return 24 * 2;
        }
        if ("1H".equals(value)) {
            return 24;
        }
        if ("4H".equals(value)) {
            return 6;
        }
        if ("1D".equals(value)) {
            return 1;
        }
        return 1;
    }

    private String tradeDate(TTbookOhlc row) {
        if (row == null) {
            return LocalDate.now().toString();
        }
        if (row.tradedate != null && row.tradedate.length() >= 10) {
            return row.tradedate.substring(0, 10);
        }
        if (row.tradeDate != null && row.tradeDate.length() >= 10) {
            return row.tradeDate.substring(0, 10);
        }
        if (row.starttime != null && row.starttime.length() >= 10) {
            return row.starttime.substring(0, 10);
        }
        return LocalDate.now().toString();
    }

    private BigDecimal calcTotalPnl(BacktestModels.BacktestResult result) {
        if (result == null) {
            return BigDecimal.ZERO;
        }
        if (result.totalPnl != null) {
            return result.totalPnl;
        }
        if (result.initialCapital == null || result.finalCapital == null) {
            return BigDecimal.ZERO;
        }
        return result.finalCapital.subtract(result.initialCapital);
    }

    private BigDecimal calcProfitFactor(List<BacktestModels.TradeRecord> tradeList) {
        if (tradeList == null || tradeList.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal positive = BigDecimal.ZERO;
        BigDecimal negative = BigDecimal.ZERO;
        for (BacktestModels.TradeRecord trade : tradeList) {
            if (trade == null || trade.returnPct == null) {
                continue;
            }
            if (trade.returnPct.compareTo(BigDecimal.ZERO) > 0) {
                positive = positive.add(trade.returnPct);
            } else if (trade.returnPct.compareTo(BigDecimal.ZERO) < 0) {
                negative = negative.add(trade.returnPct.abs());
            }
        }
        if (negative.compareTo(BigDecimal.ZERO) <= 0) {
            return scale(BigDecimal.valueOf(999D));
        }
        return scale(positive.divide(negative, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal sumReturns(List<BacktestModels.TradeRecord> tradeList) {
        BigDecimal sum = BigDecimal.ZERO;
        if (tradeList == null) {
            return sum;
        }
        for (BacktestModels.TradeRecord trade : tradeList) {
            if (trade != null && trade.returnPct != null) {
                sum = sum.add(trade.returnPct);
            }
        }
        return sum;
    }

    private long sumHoldBars(List<BacktestModels.TradeRecord> tradeList) {
        long total = 0L;
        if (tradeList == null) {
            return total;
        }
        for (BacktestModels.TradeRecord trade : tradeList) {
            if (trade != null && trade.holdBars != null) {
                total += trade.holdBars.intValue();
            }
        }
        return total;
    }

    private Integer nz(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        return nz(left).max(nz(right));
    }

    private BigDecimal add(BigDecimal left, BigDecimal right) {
        return scale(nz(left).add(nz(right)));
    }

    private BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static class WindowSlice {
        private LocalDate fitBegin;
        private LocalDate fitEnd;
        private LocalDate validateBegin;
        private LocalDate validateEnd;
        private LocalDate forwardBegin;
        private LocalDate forwardEnd;
    }
}
