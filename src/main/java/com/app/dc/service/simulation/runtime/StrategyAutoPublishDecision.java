package com.app.dc.service.simulation.runtime;

import java.util.ArrayList;
import java.util.List;

public class StrategyAutoPublishDecision {
    public boolean published;
    public String action;
    public String reason;
    public String baselineVersion;
    public Double currentTotalPnl;
    public Double currentValidatePnl;
    public Double currentForwardPnl;
    public Double currentFeeAdjustedForwardPnl;
    public Double currentForwardScore;
    public Double currentValidatePrimaryScore;
    public Double currentFeeAdjustedValidatePnl;
    public Double baselineTotalPnl;
    public Double baselineValidatePnl;
    public Double baselineForwardPnl;
    public Double baselineFeeAdjustedForwardPnl;
    public Double baselineForwardScore;
    public Double baselineValidatePrimaryScore;
    public Integer publishedCount = 0;
    public Integer skippedCount = 0;
    public List<String> publishedSymbols = new ArrayList<String>();
    public List<String> skippedSymbols = new ArrayList<String>();
    public List<SymbolDecision> symbolDecisions = new ArrayList<SymbolDecision>();

    public static class SymbolDecision {
        public String symbol;
        public boolean published;
        public String action;
        public String reason;
        public String baselineVersion;
        public String bestParamSetJson;
        public Double validatePnl;
        public Double forwardPnl;
        public Double feeAdjustedForwardPnl;
        public Double totalPnl;
        public Double validatePrimaryScore;
        public Double forwardScore;
        public Double feeAdjustedValidatePnl;
        public Integer validateTradeCount;
        public Double validateMaxDrawdownPct;
        public Double validateProfitFactor;
        public Integer oosPass;
        public Integer overfitPass;
    }
}
