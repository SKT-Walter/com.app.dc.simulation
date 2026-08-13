package com.app.dc.service.simulation;

import com.app.dc.po.Side;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class BacktestModels {

    public static final String EXECUTION_MODEL_VERSION = "v3_scene_conditioned_walk_forward";
    public static final String SCENE_CONDITIONED_WINDOW_MODE = "SCENE_CONDITIONED_WALK_FORWARD";

    private BacktestModels() {
    }

    public static class BacktestResponse {
        public String strategyName;
        public String strategyVersion;
        public String baselineVersion;
        public String runtimeType;
        public String scene;
        public String symbol;
        public List<String> symbols;
        public String text;
        public String beginDate;
        public String endDate;
        public String windowMode;
        public Integer sliceCount = 0;
        public Integer symbolCount = 0;
        public Integer fitWindowDays = 0;
        public Integer validateWindowDays = 0;
        public Integer forwardWindowDays = 0;
        public Integer minSliceCount = 0;
        public BigDecimal fitPnl = BigDecimal.ZERO;
        public BigDecimal validatePnl = BigDecimal.ZERO;
        public BigDecimal forwardPnl = BigDecimal.ZERO;
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal forwardScore = BigDecimal.ZERO;
        public BigDecimal validatePrimaryScore = BigDecimal.ZERO;
        public BigDecimal forwardAuxScore = BigDecimal.ZERO;
        public BigDecimal feeAdjustedValidatePnl = BigDecimal.ZERO;
        public BigDecimal feeAdjustedForwardPnl = BigDecimal.ZERO;
        public BigDecimal sliceParamDriftScore = BigDecimal.ZERO;
        public Integer oosPass = 0;
        public Integer overfitPass = 0;
        public String overfitReason = "";
        public String optimizationMode = "";
        public String optimizationObjective = "";
        public BigDecimal minForwardContribution = BigDecimal.ZERO;
        public Integer trialCount = 0;
        public Integer trialBudget = 0;
        public Integer trialBudgetUsed = 0;
        public Integer trialBudgetHit = 0;
        public Integer coarseCandidateCount = 0;
        public Integer fineCandidateCount = 0;
        public Integer bestRank = 0;
        public String bestParamSetJson = "{}";
        public Integer elapsedMs = 0;
        public Integer fragileBest = 0;
        public String stableParamRangeJson = "{}";
        public BigDecimal neighborAvgPnl = BigDecimal.ZERO;
        public BigDecimal neighborWorstPnl = BigDecimal.ZERO;
        public List<OptimizationTrial> trials;
        public List<BacktestResult> results;
    }

    public static class BacktestResult {
        public String executionModelVersion = EXECUTION_MODEL_VERSION;
        public String strategyName;
        public String strategyVersion;
        public String baselineVersion;
        public String runtimeType;
        public String scene;
        public String strategyPayload;
        public BigDecimal forwardScore = BigDecimal.ZERO;
        public String windowMode = "";
        public Integer sliceCount = 0;
        public Integer symbolCount = 1;
        public Integer fitWindowDays = 0;
        public Integer validateWindowDays = 0;
        public Integer forwardWindowDays = 0;
        public Integer minSliceCount = 0;
        public BigDecimal fitPnl = BigDecimal.ZERO;
        public BigDecimal validatePnl = BigDecimal.ZERO;
        public BigDecimal forwardPnl = BigDecimal.ZERO;
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal validatePrimaryScore = BigDecimal.ZERO;
        public BigDecimal forwardAuxScore = BigDecimal.ZERO;
        public BigDecimal feeAdjustedValidatePnl = BigDecimal.ZERO;
        public BigDecimal feeAdjustedForwardPnl = BigDecimal.ZERO;
        public BigDecimal sliceParamDriftScore = BigDecimal.ZERO;
        public Integer oosPass = 0;
        public Integer overfitPass = 0;
        public String overfitReason = "";
        public String optimizationMode = "";
        public String optimizationObjective = "";
        public BigDecimal minForwardContribution = BigDecimal.ZERO;
        public Integer trialCount = 0;
        public Integer trialBudget = 0;
        public Integer trialBudgetUsed = 0;
        public Integer trialBudgetHit = 0;
        public Integer coarseCandidateCount = 0;
        public Integer fineCandidateCount = 0;
        public Integer bestRank = 0;
        public String bestParamSetJson = "{}";
        public Integer elapsedMs = 0;
        public Integer fragileBest = 0;
        public String stableParamRangeJson = "{}";
        public BigDecimal neighborAvgPnl = BigDecimal.ZERO;
        public BigDecimal neighborWorstPnl = BigDecimal.ZERO;
        public String symbol;
        public String text;
        public String beginDate;
        public String endDate;
        public BigDecimal initialCapital;
        public BigDecimal finalCapital;
        public BigDecimal feeRatePct;
        public BigDecimal entryMakerFeeRatePct = BigDecimal.ZERO;
        public BigDecimal exitTakerFeeRatePct = BigDecimal.ZERO;
        public BigDecimal entryFeeTotal = BigDecimal.ZERO;
        public BigDecimal exitFeeTotal = BigDecimal.ZERO;
        public BigDecimal totalFee = BigDecimal.ZERO;
        public BigDecimal fallbackStopLossPct;
        public BigDecimal fallbackTakeProfitPct;
        public Integer maxHoldBars;
        public Integer totalBars = 0;
        public Integer tradeCount = 0;
        public Integer winCount = 0;
        public Integer lossCount = 0;
        public Integer flatCount = 0;
        public Integer stopExitCount = 0;
        public Integer takeExitCount = 0;
        public Integer stopExitWinCount = 0;
        public Integer stopExitLossCount = 0;
        public Integer takeExitWinCount = 0;
        public Integer takeExitLossCount = 0;
        public BigDecimal winRate = BigDecimal.ZERO;
        public BigDecimal totalReturnPct = BigDecimal.ZERO;
        public BigDecimal avgReturnPct = BigDecimal.ZERO;
        public BigDecimal profitFactor = BigDecimal.ZERO;
        public BigDecimal maxDrawdownPct = BigDecimal.ZERO;
        public BigDecimal avgHoldBars = BigDecimal.ZERO;
        public List<TradeRecord> tradeList;
        public List<EquityPoint> equityCurve;
        public Map<String, Integer> rejectReasonCounts;
        public List<BacktestSliceResult> sliceResults;
        public List<OptimizationTrial> optimizationTrials;
        public SceneShadowMetrics sceneShadow;
        public FullPeriodSafetyMetrics fullPeriodSafety;
    }

    /**
     * Full-period execution is retained only as a runtime safety check. Its PnL
     * never participates in candidate qualification because live entry is scene gated.
     */
    public static class FullPeriodSafetyMetrics {
        public String mode = "FULL_PERIOD_EXECUTION_SAFETY";
        public Boolean passed = false;
        public String reason = "";
        public Integer totalBars = 0;
        public Integer tradeCount = 0;
        public Map<String, Integer> rejectReasonCounts;
    }

    /**
     * Scene-conditioned replay is a formal qualification gate for candidates.
     * Live recheck tasks still remain validation-only and never publish.
     */
    public static class SceneShadowMetrics {
        public String mode = "SCENE_GATED_QUALIFICATION";
        public String status = "INSUFFICIENT_DATA";
        public String message = "";
        public Boolean qualificationPass = false;
        public String qualificationReason = "";
        public String strategyScene = "";
        public String dataBegin = "";
        public String dataEnd = "";
        public Integer sceneRecordCount = 0;
        public Integer coveredBarCount = 0;
        public Integer matchedBarCount = 0;
        public Integer blockedSignalCount = 0;
        public Integer forcedExitCount = 0;
        public Integer tradeCount = 0;
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal totalFee = BigDecimal.ZERO;
        public BigDecimal maxDrawdownPct = BigDecimal.ZERO;
        public BigDecimal profitFactor = BigDecimal.ZERO;
        public BigDecimal winRate = BigDecimal.ZERO;
    }

    public static class OptimizationTrial {
        public Integer trialNo = 0;
        public Integer sliceNo = 0;
        public String phase = "";
        public String strategyName;
        public String strategyVersion;
        public String symbolScope;
        public String textScope;
        public String paramSetJson = "{}";
        public BigDecimal fitPnl = BigDecimal.ZERO;
        public BigDecimal validatePnl = BigDecimal.ZERO;
        public BigDecimal forwardPnl = BigDecimal.ZERO;
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal forwardScore = BigDecimal.ZERO;
        public BigDecimal maxDrawdownPct = BigDecimal.ZERO;
        public Integer overfitPass = 0;
        public String overfitReason = "";
        public Integer rank = 0;
        public Integer elapsedMs = 0;
        public Integer symbolCount = 0;
        public Integer sliceCount = 0;
        public Integer fitWindowDays = 0;
        public Integer validateWindowDays = 0;
        public Integer forwardWindowDays = 0;
        public Integer minSliceCount = 0;
        public String optimizationObjective = "";
        public BigDecimal minForwardContribution = BigDecimal.ZERO;
        public Integer fragileBest = 0;
        public String stableParamRangeJson = "{}";
        public BigDecimal neighborAvgPnl = BigDecimal.ZERO;
        public BigDecimal neighborWorstPnl = BigDecimal.ZERO;
    }

    public static class BacktestSliceResult {
        public String strategyName;
        public String strategyVersion;
        public String symbol;
        public String text;
        public Integer sliceNo = 0;
        public String fitBegin;
        public String fitEnd;
        public String validateBegin;
        public String validateEnd;
        public String forwardBegin;
        public String forwardEnd;
        public BigDecimal fitPnl = BigDecimal.ZERO;
        public BigDecimal validatePnl = BigDecimal.ZERO;
        public BigDecimal forwardPnl = BigDecimal.ZERO;
        public Integer fitTradeCount = 0;
        public Integer validateTradeCount = 0;
        public Integer forwardTradeCount = 0;
        public BigDecimal fitMaxDrawdownPct = BigDecimal.ZERO;
        public BigDecimal validateMaxDrawdownPct = BigDecimal.ZERO;
        public BigDecimal forwardMaxDrawdownPct = BigDecimal.ZERO;
        public String bestParamSetJson = "{}";
        public BigDecimal fitScore = BigDecimal.ZERO;
        public BigDecimal validateScore = BigDecimal.ZERO;
        public BigDecimal forwardScore = BigDecimal.ZERO;
        public String selectionObjective = "";
        public Integer fragileBest = 0;
        public String payload = "";
    }

    public static class TradeRecord {
        public Integer tradeNo;
        public String symbol;
        public String text;
        public String side;
        public String signalTime;
        public BigDecimal signalPrice;
        public String entryTime;
        public String exitTime;
        public BigDecimal entryPrice;
        public BigDecimal exitPrice;
        public BigDecimal stopPrice;
        public BigDecimal takePrice;
        public BigDecimal qty;
        public Integer holdBars;
        public String entryReason;
        public String exitReason;
        public BigDecimal grossReturnPct;
        public BigDecimal returnPct;
        public BigDecimal pnl;
        public BigDecimal entryFeeRatePct;
        public BigDecimal exitFeeRatePct;
        public BigDecimal entryFee;
        public BigDecimal exitFee;
        public BigDecimal totalFee;
        public BigDecimal equityAfter;
        public BigDecimal cumulativePnl;
    }

    public static class Position {
        public Side side;
        public double entryPrice;
        public String entryTime;
        public String signalTime;
        public double signalPrice;
        public double entryCapital;
        public Double entryFeeRatePct;
        public double qty;
        public int entryIndex;
        public Double stopPrice;
        public Double takePrice;
        public Double trailingFirstStepPct;
        public Double trailingStepPct;
        public Double fallbackTriggerProfitPct;
        public Double fallbackTakeProfitPct;
        public int maxHoldBars;
        public int currentHoldBars;
    }

    public static class EquityPoint {
        public String time;
        public BigDecimal equity = BigDecimal.ZERO;
        public BigDecimal deltaPnl = BigDecimal.ZERO;
        public BigDecimal cumulativePnl = BigDecimal.ZERO;
    }

    public static class EquityContext {
        public double equity;
        public double peakEquity;
        public double totalPositiveReturnPct;
        public double totalNegativeReturnPct;
        public long totalHoldBars;
    }
}
