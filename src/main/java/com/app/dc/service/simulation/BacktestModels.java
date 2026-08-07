package com.app.dc.service.simulation;

import com.app.dc.po.Side;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.app.dc.service.simulation.deterministic.StrategyRoutingDecision;

public final class BacktestModels {

    private BacktestModels() {
    }

    public static class BacktestResponse {
        public String strategyName;
        public String symbol;
        public List<String> symbols;
        public String text;
        public String beginDate;
        public String endDate;
        public List<BacktestResult> results;
        public List<StrategyRoutingDecision> routingDecisions;
        public RoutingStats routingStats;
    }

    public static class RoutingStats {
        public int routingDecisionCount;
        public Map<String, Integer> routingReasonCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> selectedStrategyCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> regimeCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> candidateAcceptedCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> candidateRejectReasonCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> signalCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> strategySignalCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> strategyTradeCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> structuralTrendCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> structuralPhaseCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> signalSourceCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> structuralScoreAdjustmentCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> trendCompressionPhaseCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> trendCompressionDirectionCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> trendLifecyclePhaseCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> trendLifecycleReasonCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> ethBullTrendPhaseCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> ethBullTrendReasonCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> solBullTrendPhaseCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> solBullTrendReasonCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> ethBearTrendPhaseCounts = new java.util.LinkedHashMap<String, Integer>();
        public Map<String, Integer> ethBearTrendReasonCounts = new java.util.LinkedHashMap<String, Integer>();
    }

    public static class BacktestResult {
        public String strategyName;
        public String symbol;
        public String text;
        public String beginDate;
        public String endDate;
        public String actualBeginTime;
        public String actualEndTime;
        public BigDecimal initialCapital;
        public BigDecimal tradeNotional;
        public BigDecimal finalCapital;
        public BigDecimal feeRatePct;
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
        public Map<String, Integer> rejectReasonCounts;
    }

    public static class TradeRecord {
        public String side;
        public String entryTime;
        public String exitTime;
        public BigDecimal entryPrice;
        public BigDecimal exitPrice;
        public BigDecimal stopPrice;
        public BigDecimal takePrice;
        public Integer holdBars;
        public String exitReason;
        public String strategyName;
        public String regime;
        public BigDecimal returnPct;
        public BigDecimal pnl;
        public BigDecimal maxFavorableExcursionPct;
        public BigDecimal maxAdverseExcursionPct;
        public BigDecimal profitCaptureRatio;
        public String entryLifecyclePhase;
        public String trendTriggerType;
        public String exitLifecyclePhase;
    }

    public static class Position {
        public Side side;
        public double entryPrice;
        public String entryTime;
        public int entryIndex;
        public Double stopPrice;
        public Double takePrice;
        public int maxHoldBars;
        public int currentHoldBars;
        public String strategyName;
        public String regime;
        public int trendRegimeConflictBars;
        public int trendStructuralStrengtheningBars;
        public double lastStructuralConflictConfidence = Double.NaN;
        public double initialRiskPriceDistance = Double.NaN;
        public double entryAtr = Double.NaN;
        public double highestSinceEntry = Double.NaN;
        public double lowestSinceEntry = Double.NaN;
        public double maxFavorableExcursionPct;
        public double maxAdverseExcursionPct;
        public boolean trendTrailingActive;
        public String entryLifecyclePhase;
        public String trendTriggerType;
        public String exitLifecyclePhase;
        public String stopExitReason;
        public int ethLastFourHourIndex = -1;
        public int ethFourHourBearBars;
        public int ethLastOneHourIndex = -1;
        public int ethSoftInvalidationBars;
        public double ethSoftStopPrice = Double.NaN;
        public int ethBearLastFourHourIndex = -1;
        public int ethBearFourHourBullBars;
        public int ethBearLastOneHourIndex = -1;
        public int ethBearSoftInvalidationBars;
          public double ethBearSoftStopPrice = Double.NaN;
          public double channelBreakoutLevel = Double.NaN;
          public int channelInvalidationBars;
      }

    public static class EquityContext {
        public double equity;
        public double peakEquity;
        public double tradeNotional;
        public double totalPositiveReturnPct;
        public double totalNegativeReturnPct;
        public long totalHoldBars;
    }
}
