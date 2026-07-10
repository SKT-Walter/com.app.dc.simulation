package com.app.dc.service.simulation.runtime;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestOptimizationService;
import com.app.dc.service.simulation.BacktestSupportService;
import com.gateway.connector.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class SliceOptimizedWalkForwardRunner {

    private static final String WINDOW_MODE = "WALK_FORWARD";
    private static final String MODE_FULL_GRID_2D = "FULL_GRID_2D";

    @Autowired
    private VersionedBacktestRunner versionedBacktestRunner;

    @Autowired
    private BacktestSupportService supportService;

    @Autowired
    private BacktestOptimizationService backtestOptimizationService;

    public BacktestModels.BacktestResult run(StrategyCandidateRow candidate,
                                             BacktestParam rawParam,
                                             List<TTbookOhlc> ohlcList,
                                             BacktestOptimizationService.OptimizationPlan plan,
                                             int fitWindowDays,
                                             int validateWindowDays,
                                             int forwardWindowDays,
                                             int minSliceCount,
                                             int trialBudget) throws Exception {
        BacktestParam param = rawParam == null ? new BacktestParam() : rawParam;
        List<TTbookOhlc> rows = ohlcList == null ? new ArrayList<TTbookOhlc>() : ohlcList;
        LocalDate beginDate = LocalDate.parse(param.beginDate);
        LocalDate endDate = LocalDate.parse(param.endDate);
        LocalDate effectiveBeginDate = beginDate;
        LocalDate effectiveEndDate = endDate;
        if (!rows.isEmpty()) {
            LocalDate availableBeginDate = LocalDate.parse(tradeDate(rows.get(0)));
            LocalDate availableEndDate = LocalDate.parse(tradeDate(rows.get(rows.size() - 1)));
            if (availableBeginDate.isAfter(effectiveBeginDate)) {
                effectiveBeginDate = availableBeginDate;
            }
            if (availableEndDate.isBefore(effectiveEndDate)) {
                effectiveEndDate = availableEndDate;
            }
        }
        List<WindowSlice> slices = buildSlices(effectiveBeginDate, effectiveEndDate, fitWindowDays, validateWindowDays, forwardWindowDays);
        if (slices.size() < Math.max(1, minSliceCount)) {
            throw new IllegalStateException("window slices less than " + Math.max(1, minSliceCount));
        }

        BacktestModels.BacktestResult aggregate = initAggregate(candidate, param);
        aggregate.windowMode = WINDOW_MODE;
        aggregate.fitWindowDays = fitWindowDays;
        aggregate.validateWindowDays = validateWindowDays;
        aggregate.forwardWindowDays = forwardWindowDays;
        aggregate.minSliceCount = Math.max(1, minSliceCount);
        aggregate.sliceCount = slices.size();
        aggregate.symbolCount = 1;
        aggregate.sliceResults = new ArrayList<BacktestModels.BacktestSliceResult>();
        aggregate.optimizationTrials = new ArrayList<BacktestModels.OptimizationTrial>();

        BigDecimal fitPnl = BigDecimal.ZERO;
        BigDecimal validatePnl = BigDecimal.ZERO;
        BigDecimal forwardPnl = BigDecimal.ZERO;
        BigDecimal totalValidateFee = BigDecimal.ZERO;
        BigDecimal forwardScoreSum = BigDecimal.ZERO;
        BigDecimal validateScoreSum = BigDecimal.ZERO;
        int perSliceBudget = resolvePerSliceBudget(trialBudget, slices.size());
        List<Map<String, Object>> selectedParamSets = new ArrayList<Map<String, Object>>();
        int fragileCount = 0;
        int nextTrialNo = 1;

        for (int i = 0; i < slices.size(); i++) {
            BacktestExecutionGuard.checkInterrupted("slice_optimized_slice_loop");
            WindowSlice slice = slices.get(i);
            List<TTbookOhlc> fitRows = filterRows(rows, slice.fitBegin, slice.fitEnd);
            List<TTbookOhlc> validateRows = filterRows(rows, slice.validateBegin, slice.validateEnd);
            List<TTbookOhlc> forwardRows = filterRows(rows, slice.forwardBegin, slice.forwardEnd);
            ensureNonEmpty("fit", fitRows, candidate, param, slice.fitBegin, slice.fitEnd);
            ensureNonEmpty("validate", validateRows, candidate, param, slice.validateBegin, slice.validateEnd);
            ensureNonEmpty("forward", forwardRows, candidate, param, slice.forwardBegin, slice.forwardEnd);

            SliceSelection selection = selectBestParamForSlice(candidate, param, plan, fitRows, slice, i + 1, perSliceBudget, nextTrialNo);
            nextTrialNo = selection.nextTrialNo;
            selectedParamSets.add(selection.paramSet);
            fragileCount += selection.fragileBest;
            if (selection.optimizationTrials != null && !selection.optimizationTrials.isEmpty()) {
                aggregate.optimizationTrials.addAll(selection.optimizationTrials);
            }

            BacktestModels.BacktestResult fitResult = selection.fitResult;
            BacktestModels.BacktestResult validateResult = runWindow(candidate, param, selection.paramSet, validateRows, slice.validateBegin, slice.validateEnd);
            BacktestModels.BacktestResult forwardResult = runWindow(candidate, param, selection.paramSet, forwardRows, slice.forwardBegin, slice.forwardEnd);

            fitPnl = fitPnl.add(nz(fitResult.totalPnl));
            validatePnl = validatePnl.add(nz(validateResult.totalPnl));
            forwardPnl = forwardPnl.add(nz(forwardResult.totalPnl));
            totalValidateFee = totalValidateFee.add(nz(validateResult.totalFee));
            validateScoreSum = validateScoreSum.add(nz(validateResult.totalPnl));
            forwardScoreSum = forwardScoreSum.add(nz(forwardResult.totalReturnPct));

            mergeValidatePhase(aggregate, validateResult);
            aggregate.maxDrawdownPct = max(aggregate.maxDrawdownPct, validateResult.maxDrawdownPct);
            aggregate.totalBars += nzInt(validateResult.totalBars);
            aggregate.sliceResults.add(buildSlice(candidate, param, i + 1, slice, selection, fitResult, validateResult, forwardResult));
        }

        aggregate.fitPnl = scale(fitPnl);
        aggregate.validatePnl = scale(validatePnl);
        aggregate.forwardPnl = scale(forwardPnl);
        aggregate.totalPnl = scale(validatePnl);
        aggregate.validatePrimaryScore = scale(validateScoreSum);
        aggregate.forwardAuxScore = slices.isEmpty()
                ? BigDecimal.ZERO
                : scale(forwardScoreSum.divide(BigDecimal.valueOf(slices.size()), 6, RoundingMode.HALF_UP));
        aggregate.forwardScore = aggregate.forwardAuxScore;
        aggregate.totalFee = aggregate.totalFee == null ? BigDecimal.ZERO : aggregate.totalFee;
        aggregate.feeAdjustedValidatePnl = scale(validatePnl);
        aggregate.feeAdjustedForwardPnl = scale(forwardPnl);
        aggregate.finalCapital = scale(nz(aggregate.initialCapital).add(aggregate.validatePnl));
        aggregate.totalReturnPct = nz(aggregate.initialCapital).compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : scale(aggregate.validatePnl.divide(aggregate.initialCapital, 6, RoundingMode.HALF_UP));
        aggregate.profitFactor = calcProfitFactor(aggregate.tradeList);
        aggregate.winRate = nzInt(aggregate.tradeCount) <= 0
                ? BigDecimal.ZERO
                : scale(BigDecimal.valueOf(nzInt(aggregate.winCount))
                .divide(BigDecimal.valueOf(nzInt(aggregate.tradeCount)), 6, RoundingMode.HALF_UP));
        aggregate.avgReturnPct = nzInt(aggregate.tradeCount) <= 0
                ? BigDecimal.ZERO
                : scale(sumReturns(aggregate.tradeList)
                .divide(BigDecimal.valueOf(nzInt(aggregate.tradeCount)), 6, RoundingMode.HALF_UP));
        aggregate.avgHoldBars = nzInt(aggregate.tradeCount) <= 0
                ? BigDecimal.ZERO
                : scale(BigDecimal.valueOf(sumHoldBars(aggregate.tradeList))
                .divide(BigDecimal.valueOf(nzInt(aggregate.tradeCount)), 6, RoundingMode.HALF_UP));
        aggregate.fragileBest = slices.isEmpty() ? 0 : (fragileCount > 0 ? 1 : 0);
        aggregate.sliceParamDriftScore = scale(calculateSliceParamDrift(selectedParamSets, plan));
        GateDecision gate = evaluateOosGate(aggregate.fitPnl, aggregate.validatePnl, aggregate.forwardPnl, aggregate.tradeCount);
        aggregate.oosPass = gate.oosPass ? 1 : 0;
        aggregate.overfitPass = gate.overfitPass ? 1 : 0;
        aggregate.overfitReason = gate.reason;
        aggregate.bestParamSetJson = buildRepresentativeParamSetJson(selectedParamSets);
        aggregate.stableParamRangeJson = buildStableParamSummaryJson(selectedParamSets);
        aggregate.neighborAvgPnl = BigDecimal.ZERO;
        aggregate.neighborWorstPnl = BigDecimal.ZERO;
        aggregate.trialCount = aggregate.optimizationTrials == null ? 0 : aggregate.optimizationTrials.size();
        return aggregate;
    }

    private SliceSelection selectBestParamForSlice(StrategyCandidateRow candidate,
                                                   BacktestParam baseParam,
                                                   BacktestOptimizationService.OptimizationPlan plan,
                                                   List<TTbookOhlc> fitRows,
                                                   WindowSlice slice,
                                                   int sliceNo,
                                                   int sliceBudget,
                                                   int startTrialNo) throws Exception {
        SliceSelection selection = new SliceSelection();
        selection.nextTrialNo = startTrialNo;
        List<Map<String, Object>> coarseSets = plan == null || !plan.optimizationSupported
                ? backtestOptimizationService.buildDefaultOnly(plan)
                : backtestOptimizationService.buildCoarseParamSets(plan);
        boolean fullGrid2d = isFullGrid2d(plan);
        int coarseBudget = fullGrid2d ? coarseSets.size() : Math.max(1, sliceBudget / 2);
        coarseSets = limit(coarseSets, coarseBudget);
        List<SliceFitTrial> coarseTrials = executeFitTrials(candidate, baseParam, fitRows, slice, coarseSets, "COARSE", selection.nextTrialNo);
        selection.nextTrialNo += coarseTrials.size();
        List<BacktestModels.OptimizationTrial> rankedCoarseTrials =
                toOptimizationTrials(candidate, baseParam, plan, sliceNo, coarseTrials);
        backtestOptimizationService.rankFitTrials(plan, rankedCoarseTrials);

        List<Map<String, Object>> fineSets = fullGrid2d || plan == null || !plan.optimizationSupported
                ? Collections.<Map<String, Object>>emptyList()
                : backtestOptimizationService.buildFineParamSets(plan, rankedCoarseTrials);
        fineSets = limit(fineSets, Math.max(0, sliceBudget - coarseTrials.size()));
        List<SliceFitTrial> fineTrials = executeFitTrials(candidate, baseParam, fitRows, slice, fineSets, "FINE", selection.nextTrialNo);
        selection.nextTrialNo += fineTrials.size();

        List<SliceFitTrial> allTrials = new ArrayList<SliceFitTrial>();
        allTrials.addAll(coarseTrials);
        allTrials.addAll(fineTrials);
        if (allTrials.isEmpty()) {
            Map<String, Object> defaultParamSet = plan == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(plan.defaultParams);
            BacktestModels.BacktestResult fitResult = runWindow(candidate, baseParam, defaultParamSet, fitRows, slice.fitBegin, slice.fitEnd);
            selection.paramSet = defaultParamSet;
            selection.fitResult = fitResult;
            selection.selectionObjective = plan == null ? "" : plan.objective;
            selection.fitScore = nz(fitResult.totalPnl);
            selection.fragileBest = 1;
            selection.optimizationTrials = Collections.emptyList();
            return selection;
        }
        List<BacktestModels.OptimizationTrial> ranked =
                toOptimizationTrials(candidate, baseParam, plan, sliceNo, allTrials);
        backtestOptimizationService.rankFitTrials(plan, ranked);
        selection.optimizationTrials = ranked;
        Map<String, BacktestModels.OptimizationTrial> byParam = new LinkedHashMap<String, BacktestModels.OptimizationTrial>();
        for (BacktestModels.OptimizationTrial trial : ranked) {
            byParam.put(trial.paramSetJson, trial);
        }
        Collections.sort(allTrials, new Comparator<SliceFitTrial>() {
            @Override
            public int compare(SliceFitTrial left, SliceFitTrial right) {
                BacktestModels.OptimizationTrial l = byParam.get(left.paramSetJson);
                BacktestModels.OptimizationTrial r = byParam.get(right.paramSetJson);
                int lr = l == null || l.rank == null ? Integer.MAX_VALUE : l.rank.intValue();
                int rr = r == null || r.rank == null ? Integer.MAX_VALUE : r.rank.intValue();
                return Integer.compare(lr, rr);
            }
        });
        SliceFitTrial best = allTrials.get(0);
        BacktestModels.OptimizationTrial bestRanked = byParam.get(best.paramSetJson);
        selection.paramSet = best.paramSet;
        selection.fitResult = best.fitResult;
        selection.selectionObjective = plan == null ? "" : plan.objective;
        selection.fitScore = nz(best.fitResult.totalPnl);
        selection.fragileBest = bestRanked != null && bestRanked.fragileBest != null ? bestRanked.fragileBest.intValue() : 0;
        selection.neighborAvgPnl = bestRanked == null ? BigDecimal.ZERO : nz(bestRanked.neighborAvgPnl);
        selection.neighborWorstPnl = bestRanked == null ? BigDecimal.ZERO : nz(bestRanked.neighborWorstPnl);
        return selection;
    }

    private List<SliceFitTrial> executeFitTrials(StrategyCandidateRow candidate,
                                                 BacktestParam baseParam,
                                                 List<TTbookOhlc> fitRows,
                                                 WindowSlice slice,
                                                 List<Map<String, Object>> paramSets,
                                                 String phase,
                                                 int startTrialNo) throws Exception {
        List<SliceFitTrial> results = new ArrayList<SliceFitTrial>();
        if (paramSets == null) {
            return results;
        }
        int trialNo = Math.max(1, startTrialNo);
        for (Map<String, Object> paramSet : paramSets) {
            BacktestExecutionGuard.checkInterrupted("slice_fit_trial_loop");
            BacktestModels.BacktestResult fitResult = runWindow(candidate, baseParam, paramSet, fitRows, slice.fitBegin, slice.fitEnd);
            SliceFitTrial trial = new SliceFitTrial();
            trial.trialNo = trialNo++;
            trial.phase = phase;
            trial.paramSet = paramSet == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(paramSet);
            trial.paramSetJson = JsonUtils.Serializer(trial.paramSet);
            trial.fitResult = fitResult;
            results.add(trial);
        }
        return results;
    }

    private int resolvePerSliceBudget(int trialBudget, int sliceCount) {
        int safeBudget = Math.max(1, trialBudget);
        int safeSlices = Math.max(1, sliceCount);
        int average = (int) Math.ceil((double) safeBudget / (double) safeSlices);
        return Math.max(4, average);
    }

    private boolean isFullGrid2d(BacktestOptimizationService.OptimizationPlan plan) {
        return plan != null && MODE_FULL_GRID_2D.equalsIgnoreCase(plan.optimizationMode);
    }

    private GateDecision evaluateOosGate(BigDecimal fitPnl,
                                         BigDecimal validatePnl,
                                         BigDecimal forwardPnl,
                                         Integer tradeCount) {
        GateDecision gate = new GateDecision();
        gate.overfitPass = true;
        gate.oosPass = false;
        gate.reason = "";
        if (nz(fitPnl).compareTo(BigDecimal.ZERO) > 0
                && nz(validatePnl).compareTo(BigDecimal.ZERO) <= 0) {
            gate.overfitPass = false;
            gate.reason = "fit_pnl > 0 but validate_pnl <= 0";
        } else if (nz(validatePnl).compareTo(BigDecimal.ZERO) > 0
                && nz(forwardPnl).compareTo(BigDecimal.ZERO) <= 0) {
            gate.overfitPass = false;
            gate.reason = "validate_pnl > 0 but forward_pnl <= 0";
        }
        gate.oosPass = gate.overfitPass
                && nz(validatePnl).compareTo(BigDecimal.ZERO) > 0
                && nz(forwardPnl).compareTo(BigDecimal.ZERO) > 0
                && nzInt(tradeCount) > 0;
        return gate;
    }

    private List<BacktestModels.OptimizationTrial> toOptimizationTrials(StrategyCandidateRow candidate,
                                                                        BacktestParam baseParam,
                                                                        BacktestOptimizationService.OptimizationPlan plan,
                                                                        int sliceNo,
                                                                        List<SliceFitTrial> trials) {
        List<BacktestModels.OptimizationTrial> results = new ArrayList<BacktestModels.OptimizationTrial>();
        if (trials == null) {
            return results;
        }
        for (SliceFitTrial trial : trials) {
            BacktestModels.OptimizationTrial row = new BacktestModels.OptimizationTrial();
            row.trialNo = trial.trialNo;
            row.sliceNo = sliceNo;
            row.phase = trial.phase;
            row.strategyName = candidate == null ? "" : candidate.strategyName;
            row.strategyVersion = candidate == null ? "" : candidate.strategyVersion;
            row.symbolScope = baseParam == null ? "" : baseParam.symbol;
            row.textScope = baseParam == null ? "" : baseParam.text;
            row.paramSetJson = trial.paramSetJson;
            row.fitPnl = nz(trial.fitResult == null ? null : trial.fitResult.totalPnl);
            row.totalPnl = row.fitPnl;
            row.maxDrawdownPct = nz(trial.fitResult == null ? null : trial.fitResult.maxDrawdownPct);
            row.overfitPass = 1;
            row.sliceCount = 1;
            row.symbolCount = 1;
            row.fitWindowDays = plan == null ? 0 : plan.fitWindowDays;
            row.validateWindowDays = plan == null ? 0 : plan.validateWindowDays;
            row.forwardWindowDays = plan == null ? 0 : plan.forwardWindowDays;
            row.minSliceCount = plan == null ? 0 : plan.minSliceCount;
            row.optimizationObjective = plan == null ? "" : plan.objective;
            row.minForwardContribution = plan == null ? BigDecimal.ZERO : nz(plan.minForwardContribution);
            results.add(row);
        }
        return results;
    }

    private BacktestModels.BacktestResult runWindow(StrategyCandidateRow candidate,
                                                    BacktestParam baseParam,
                                                    Map<String, Object> paramSet,
                                                    List<TTbookOhlc> rows,
                                                    LocalDate begin,
                                                    LocalDate end) throws Exception {
        BacktestParam target = copyParam(baseParam, begin, end);
        applyTrialParamOverrides(target, paramSet);
        return versionedBacktestRunner.run(candidate, target, rows);
    }

    private BacktestModels.BacktestResult initAggregate(StrategyCandidateRow candidate, BacktestParam param) {
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
        result.tradeList = new ArrayList<BacktestModels.TradeRecord>();
        result.equityCurve = new ArrayList<BacktestModels.EquityPoint>();
        result.rejectReasonCounts = new LinkedHashMap<String, Integer>();
        return result;
    }

    private void mergeValidatePhase(BacktestModels.BacktestResult target, BacktestModels.BacktestResult phase) {
        if (phase == null) {
            return;
        }
        target.tradeCount += nzInt(phase.tradeCount);
        target.winCount += nzInt(phase.winCount);
        target.lossCount += nzInt(phase.lossCount);
        target.flatCount += nzInt(phase.flatCount);
        target.stopExitCount += nzInt(phase.stopExitCount);
        target.takeExitCount += nzInt(phase.takeExitCount);
        target.stopExitWinCount += nzInt(phase.stopExitWinCount);
        target.stopExitLossCount += nzInt(phase.stopExitLossCount);
        target.takeExitWinCount += nzInt(phase.takeExitWinCount);
        target.takeExitLossCount += nzInt(phase.takeExitLossCount);
        target.entryFeeTotal = add(target.entryFeeTotal, phase.entryFeeTotal);
        target.exitFeeTotal = add(target.exitFeeTotal, phase.exitFeeTotal);
        target.totalFee = add(target.totalFee, phase.totalFee);
        if (phase.tradeList != null && !phase.tradeList.isEmpty()) {
            target.tradeList.addAll(phase.tradeList);
        }
        mergeRejectReasons(target.rejectReasonCounts, phase.rejectReasonCounts);
    }

    private BacktestModels.BacktestSliceResult buildSlice(StrategyCandidateRow candidate,
                                                          BacktestParam param,
                                                          int sliceNo,
                                                          WindowSlice slice,
                                                          SliceSelection selection,
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
        row.fitPnl = scale(nz(fitResult.totalPnl));
        row.validatePnl = scale(nz(validateResult.totalPnl));
        row.forwardPnl = scale(nz(forwardResult.totalPnl));
        row.fitTradeCount = nzInt(fitResult.tradeCount);
        row.validateTradeCount = nzInt(validateResult.tradeCount);
        row.forwardTradeCount = nzInt(forwardResult.tradeCount);
        row.fitMaxDrawdownPct = scale(nz(fitResult.maxDrawdownPct));
        row.validateMaxDrawdownPct = scale(nz(validateResult.maxDrawdownPct));
        row.forwardMaxDrawdownPct = scale(nz(forwardResult.maxDrawdownPct));
        row.bestParamSetJson = JsonUtils.Serializer(selection.paramSet);
        row.fitScore = scale(selection.fitScore);
        row.validateScore = scale(nz(validateResult.totalPnl));
        row.forwardScore = scale(nz(forwardResult.totalReturnPct));
        row.selectionObjective = selection.selectionObjective == null ? "" : selection.selectionObjective;
        row.fragileBest = selection.fragileBest;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("bestParamSetJson", row.bestParamSetJson);
        payload.put("fitScore", row.fitScore);
        payload.put("validateScore", row.validateScore);
        payload.put("forwardScore", row.forwardScore);
        payload.put("neighborAvgPnl", selection.neighborAvgPnl);
        payload.put("neighborWorstPnl", selection.neighborWorstPnl);
        row.payload = JsonUtils.Serializer(payload);
        return row;
    }

    private void mergeRejectReasons(Map<String, Integer> target, Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            Integer prev = target.get(entry.getKey());
            target.put(entry.getKey(), Integer.valueOf((prev == null ? 0 : prev.intValue()) + nzInt(entry.getValue())));
        }
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
        if (rows == null) {
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
        if (source.strategyParams != null) {
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

    private void applyTrialParamOverrides(BacktestParam param, Map<String, Object> trialParams) {
        if (param == null || trialParams == null || trialParams.isEmpty()) {
            return;
        }
        if (param.strategyParams == null) {
            param.strategyParams = new LinkedHashMap<String, Object>();
        }
        for (Map.Entry<String, Object> entry : trialParams.entrySet()) {
            param.strategyParams.put(entry.getKey(), entry.getValue());
        }
    }

    private void ensureNonEmpty(String segment, List<TTbookOhlc> rows, StrategyCandidateRow candidate, BacktestParam param,
                                LocalDate begin, LocalDate end) {
        if (rows != null && !rows.isEmpty()) {
            return;
        }
        throw new IllegalStateException("insufficient " + segment + " rows for "
                + candidate.strategyName + "@" + candidate.strategyVersion + " "
                + param.symbol + " " + begin + "~" + end);
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> paramSets, int size) {
        if (paramSets == null || paramSets.isEmpty() || paramSets.size() <= size) {
            return paramSets == null ? Collections.<Map<String, Object>>emptyList() : paramSets;
        }
        if (size <= 0) {
            return Collections.emptyList();
        }
        return new ArrayList<Map<String, Object>>(paramSets.subList(0, size));
    }

    private String buildRepresentativeParamSetJson(List<Map<String, Object>> selectedParamSets) {
        if (selectedParamSets == null || selectedParamSets.isEmpty()) {
            return "{}";
        }
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        Map<String, Object> latestByKey = new LinkedHashMap<String, Object>();
        for (Map<String, Object> paramSet : selectedParamSets) {
            if (paramSet == null || paramSet.isEmpty()) {
                continue;
            }
            String key = JsonUtils.Serializer(new LinkedHashMap<String, Object>(paramSet));
            counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            latestByKey.put(key, new LinkedHashMap<String, Object>(paramSet));
        }
        String bestKey = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= bestCount) {
                bestKey = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (bestKey == null) {
            return "{}";
        }
        return JsonUtils.Serializer(latestByKey.get(bestKey));
    }

    private String buildStableParamSummaryJson(List<Map<String, Object>> selectedParamSets) {
        Map<String, Set<Object>> summary = new LinkedHashMap<String, Set<Object>>();
        if (selectedParamSets != null) {
            for (Map<String, Object> paramSet : selectedParamSets) {
                if (paramSet == null) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : paramSet.entrySet()) {
                    Set<Object> values = summary.get(entry.getKey());
                    if (values == null) {
                        values = new LinkedHashSet<Object>();
                        summary.put(entry.getKey(), values);
                    }
                    values.add(entry.getValue());
                }
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Set<Object>> entry : summary.entrySet()) {
            normalized.put(entry.getKey(), new ArrayList<Object>(entry.getValue()));
        }
        return JsonUtils.Serializer(normalized);
    }

    private BigDecimal calculateSliceParamDrift(List<Map<String, Object>> selectedParamSets,
                                                BacktestOptimizationService.OptimizationPlan plan) {
        if (selectedParamSets == null || selectedParamSets.size() <= 1 || plan == null || plan.dimensions == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalDrift = BigDecimal.ZERO;
        int compared = 0;
        for (BacktestOptimizationService.ParameterDimension dimension : plan.dimensions) {
            if (dimension == null || dimension.candidates == null || dimension.candidates.isEmpty()) {
                continue;
            }
            int drift = 0;
            int steps = 0;
            for (int i = 1; i < selectedParamSets.size(); i++) {
                int prev = indexOf(dimension.candidates, valueOf(selectedParamSets.get(i - 1), dimension.name));
                int curr = indexOf(dimension.candidates, valueOf(selectedParamSets.get(i), dimension.name));
                if (prev >= 0 && curr >= 0) {
                    drift += Math.abs(curr - prev);
                    steps++;
                }
            }
            if (steps > 0) {
                totalDrift = totalDrift.add(BigDecimal.valueOf(drift)
                        .divide(BigDecimal.valueOf(steps), 6, RoundingMode.HALF_UP));
                compared++;
            }
        }
        if (compared <= 0) {
            return BigDecimal.ZERO;
        }
        return totalDrift.divide(BigDecimal.valueOf(compared), 6, RoundingMode.HALF_UP);
    }

    private int indexOf(List<Object> candidates, Object value) {
        if (candidates == null) {
            return -1;
        }
        for (int i = 0; i < candidates.size(); i++) {
            Object candidate = candidates.get(i);
            if (candidate == null && value == null) {
                return i;
            }
            if (candidate != null && value != null && String.valueOf(candidate).equals(String.valueOf(value))) {
                return i;
            }
        }
        return -1;
    }

    private Object valueOf(Map<String, Object> paramSet, String name) {
        return paramSet == null ? null : paramSet.get(name);
    }

    private BigDecimal sumReturns(List<BacktestModels.TradeRecord> tradeList) {
        BigDecimal result = BigDecimal.ZERO;
        if (tradeList == null) {
            return result;
        }
        for (BacktestModels.TradeRecord trade : tradeList) {
            if (trade != null && trade.returnPct != null) {
                result = result.add(trade.returnPct);
            }
        }
        return result;
    }

    private long sumHoldBars(List<BacktestModels.TradeRecord> tradeList) {
        long result = 0L;
        if (tradeList == null) {
            return result;
        }
        for (BacktestModels.TradeRecord trade : tradeList) {
            if (trade != null && trade.holdBars != null) {
                result += trade.holdBars.longValue();
            }
        }
        return result;
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

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        return nz(left).max(nz(right));
    }

    private BigDecimal add(BigDecimal left, BigDecimal right) {
        return nz(left).add(nz(right));
    }

    private BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer nzInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static class WindowSlice {
        private LocalDate fitBegin;
        private LocalDate fitEnd;
        private LocalDate validateBegin;
        private LocalDate validateEnd;
        private LocalDate forwardBegin;
        private LocalDate forwardEnd;
    }

    private static class SliceSelection {
        private Map<String, Object> paramSet = new LinkedHashMap<String, Object>();
        private BacktestModels.BacktestResult fitResult;
        private BigDecimal fitScore = BigDecimal.ZERO;
        private String selectionObjective = "";
        private int fragileBest = 0;
        private BigDecimal neighborAvgPnl = BigDecimal.ZERO;
        private BigDecimal neighborWorstPnl = BigDecimal.ZERO;
        private List<BacktestModels.OptimizationTrial> optimizationTrials = Collections.emptyList();
        private int nextTrialNo = 1;
    }

    private static class SliceFitTrial {
        private Integer trialNo = 0;
        private String phase = "";
        private Map<String, Object> paramSet = new LinkedHashMap<String, Object>();
        private String paramSetJson = "{}";
        private BacktestModels.BacktestResult fitResult;
    }

    private static class GateDecision {
        private boolean overfitPass;
        private boolean oosPass;
        private String reason;
    }
}
