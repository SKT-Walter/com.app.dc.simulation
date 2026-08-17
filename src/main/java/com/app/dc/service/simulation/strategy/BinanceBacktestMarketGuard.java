package com.app.dc.service.simulation.strategy;

import com.app.dc.po.chatGPT.TTChatGPTAnalysis;
import com.app.dc.po.sentiment.TTChatGPTSentiment;
import com.app.dc.service.dao.ChatGPTAnalysisQueryService;
import com.app.dc.service.dao.ChatGPTSentimentQueryService;
import com.app.dc.strategy.core.strategy.SymbolStrategyNames;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BinanceBacktestMarketGuard {

    private static final double BLOCK_CONFIDENCE = 0.8D;

    private final ChatGPTAnalysisQueryService analysisQueryService;
    private final ChatGPTSentimentQueryService sentimentQueryService;

    @Value("${binanceBacktestStageGuardEnabled:true}")
    private boolean stageGuardEnabled;

    @Value("${binanceBacktestSentimentGuardEnabled:true}")
    private boolean sentimentGuardEnabled;

    @Value("${binanceBacktestRangeAllowedStages:0,2,B}")
    private String rangeAllowedStagesConfig;

    @Value("${binanceBacktestChannelAllowedStages:1,2,3,4,A,B,C}")
    private String channelAllowedStagesConfig;

    @Value("${binanceBacktestTrendAllowedStages:1,3,4,A,C}")
    private String trendAllowedStagesConfig;

    public BinanceBacktestMarketGuard(ChatGPTAnalysisQueryService analysisQueryService,
                                      ChatGPTSentimentQueryService sentimentQueryService) {
        this.analysisQueryService = analysisQueryService;
        this.sentimentQueryService = sentimentQueryService;
    }

    public GuardContext prepareContext(String symbol, String beginDate, String endDate) {
        if (!stageGuardEnabled && !sentimentGuardEnabled) {
            GuardContext disabled = new GuardContext();
            disabled.analyses = new ArrayList<>();
            disabled.sentiments = new ArrayList<>();
            return disabled;
        }
        String beginTime = normalizeBegin(beginDate);
        String endTime = normalizeEnd(endDate);

        List<TimedAnalysis> analyses = analysisQueryService.queryAnalysis(symbol, beginTime, endTime, 20000)
                .stream()
                .map(this::toTimedAnalysis)
                .filter(v -> v != null)
                .sorted(Comparator.comparing(v -> v.time))
                .collect(Collectors.toCollection(ArrayList::new));

        List<TimedSentiment> sentiments = sentimentQueryService.querySentiment("GLOBAL_MARKET", beginTime, endTime, 20000)
                .stream()
                .map(this::toTimedSentiment)
                .filter(v -> v != null)
                .sorted(Comparator.comparing(v -> v.time))
                .collect(Collectors.toCollection(ArrayList::new));

        GuardContext ctx = new GuardContext();
        ctx.analyses = analyses;
        ctx.sentiments = sentiments;
        return ctx;
    }

    public boolean shouldBlock(String strategyName, GuardContext ctx, Instant barTime, boolean ignoreSentimentGuard) {
        if (ctx == null || barTime == null) {
            return false;
        }

        String strategyType = toStrategyType(strategyName);
        if (!ignoreSentimentGuard && sentimentGuardEnabled && shouldBlockBySentiment(ctx, barTime)) {
            return true;
        }
        if (stageGuardEnabled && shouldBlockByStage(strategyType, ctx, barTime)) {
            return true;
        }
        return false;
    }

    private boolean shouldBlockBySentiment(GuardContext ctx, Instant barTime) {
        TTChatGPTSentiment sentiment = latestSentimentAt(ctx, barTime);
        if (sentiment == null) {
            return false;
        }
        Double confidence = sentiment.confidence;
        String riskLevel = sentiment.risk_level;
        if (confidence == null || StringUtils.isBlank(riskLevel)) {
            return false;
        }
        boolean highRisk = "high".equalsIgnoreCase(riskLevel) || "extreme".equalsIgnoreCase(riskLevel);
        boolean highConfidence = confidence > BLOCK_CONFIDENCE;
        if (highRisk && highConfidence) {
            log.info("backtest sentiment blocked, riskLevel:{}, confidence:{}, barTime:{}",
                    riskLevel, confidence, barTime);
            return true;
        }
        return false;
    }

    private boolean shouldBlockByStage(String strategyType, GuardContext ctx, Instant barTime) {
        TTChatGPTAnalysis analysis = latestAnalysisAt(ctx, barTime);
        if (analysis == null || StringUtils.isBlank(analysis.latest_stage)) {
            log.info("backtest stage blocked, strategyType:{}, reason:no_analysis, barTime:{}", strategyType, barTime);
            return true;
        }

        String latestStage = analysis.latest_stage.trim().toUpperCase(Locale.ROOT);
        Set<String> allowStages = allowedStages(strategyType);
        boolean blocked = !allowStages.contains(latestStage);
        if (blocked) {
            log.info("backtest stage blocked, strategyType:{}, latestStage:{}, allowStages:{}, barTime:{}",
                    strategyType, latestStage, allowStages, barTime);
        }
        return blocked;
    }

    private TTChatGPTAnalysis latestAnalysisAt(GuardContext ctx, Instant barTime) {
        while (ctx.analysisCursor + 1 < ctx.analyses.size()
                && !ctx.analyses.get(ctx.analysisCursor + 1).time.isAfter(barTime)) {
            ctx.analysisCursor++;
            ctx.currentAnalysis = ctx.analyses.get(ctx.analysisCursor).data;
        }
        return ctx.currentAnalysis;
    }

    private TTChatGPTSentiment latestSentimentAt(GuardContext ctx, Instant barTime) {
        while (ctx.sentimentCursor + 1 < ctx.sentiments.size()
                && !ctx.sentiments.get(ctx.sentimentCursor + 1).time.isAfter(barTime)) {
            ctx.sentimentCursor++;
            ctx.currentSentiment = ctx.sentiments.get(ctx.sentimentCursor).data;
        }
        return ctx.currentSentiment;
    }

    private String toStrategyType(String strategyName) {
        if (StringUtils.isBlank(strategyName)) {
            return "trend";
        }
        String name = SymbolStrategyNames.baseName(strategyName.trim());
        if ("binanceChannel".equalsIgnoreCase(name)) {
            return "channel";
        }
        if ("binanceTrend".equalsIgnoreCase(name)
                || "ethStructuralBullTrend".equalsIgnoreCase(name)
                || "btcStructuralBullTrend".equalsIgnoreCase(name)
                || "btcBullLaunchTrend".equalsIgnoreCase(name)
                || "btcStructuralBearTrend".equalsIgnoreCase(name)
                || "ethStructuralBearTrend".equalsIgnoreCase(name)
                || "solStructuralBearTrend".equalsIgnoreCase(name)
                || "solMomentumBullTrend".equalsIgnoreCase(name)
                || "breakoutRetestContinuationTrend".equalsIgnoreCase(name)
                || "emaPullbackBuy".equalsIgnoreCase(name)
                || "trendRestart".equalsIgnoreCase(name)
                || "smallRangeBreakout".equalsIgnoreCase(name)
                || "strongMomentumContinuation".equalsIgnoreCase(name)
                || "trendPullbackRecovery".equalsIgnoreCase(name)) {
            return "trend";
        }
        // 鍏朵綑闇囪崱绫荤瓥鐣ョ粺涓€鎸?range 鍦烘櫙杩囨护
        if ("binanceRange".equalsIgnoreCase(name)
                || "binanceRangeMacd".equalsIgnoreCase(name)
                || "bollingerMeanReversion".equalsIgnoreCase(name)
                || "breakoutRetestContinuation".equalsIgnoreCase(name)
                || "compressionBreak".equalsIgnoreCase(name)
                || "failedBreakReversal".equalsIgnoreCase(name)
                || "impulseReclaim".equalsIgnoreCase(name)
                || "rsiKdjReversion".equalsIgnoreCase(name)
                || "donchianReversion".equalsIgnoreCase(name)
                || "vwapReversion".equalsIgnoreCase(name)
                || "zscoreReversion".equalsIgnoreCase(name)
                || "atrChannelReversion".equalsIgnoreCase(name)
                || "orderBookImbalanceReversion".equalsIgnoreCase(name)) {
            return "range";
        }
        return "trend";
    }

    private Set<String> allowedStages(String strategyType) {
        String cfg;
        switch (strategyType) {
            case "range":
                cfg = rangeAllowedStagesConfig;
                break;
            case "channel":
                cfg = channelAllowedStagesConfig;
                break;
            case "trend":
            default:
                cfg = trendAllowedStagesConfig;
                break;
        }
        return parseStages(cfg);
    }

    private Set<String> parseStages(String config) {
        if (StringUtils.isBlank(config)) {
            return new HashSet<>();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(v -> v.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private TimedAnalysis toTimedAnalysis(TTChatGPTAnalysis data) {
        Instant time = parseTime(data == null ? null : data.analysis_time);
        return time == null || data == null ? null : new TimedAnalysis(time, data);
    }

    private TimedSentiment toTimedSentiment(TTChatGPTSentiment data) {
        Instant time = parseTime(data == null ? null : data.analysis_time);
        return time == null || data == null ? null : new TimedSentiment(time, data);
    }

    private Instant parseTime(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (Exception ignore) {
        }
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(text, formatter);
                return ldt.atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private String normalizeBegin(String beginDate) {
        if (StringUtils.isBlank(beginDate)) {
            return "1970-01-01 00:00:00";
        }
        return LocalDate.parse(beginDate.trim()).atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String normalizeEnd(String endDate) {
        if (StringUtils.isBlank(endDate)) {
            return LocalDate.now().plusDays(1).atTime(23, 59, 59)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return LocalDate.parse(endDate.trim()).atTime(23, 59, 59)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public static class GuardContext {
        private List<TimedAnalysis> analyses = new ArrayList<>();
        private List<TimedSentiment> sentiments = new ArrayList<>();
        private int analysisCursor = -1;
        private int sentimentCursor = -1;
        private TTChatGPTAnalysis currentAnalysis;
        private TTChatGPTSentiment currentSentiment;
    }

    private static class TimedAnalysis {
        private final Instant time;
        private final TTChatGPTAnalysis data;

        private TimedAnalysis(Instant time, TTChatGPTAnalysis data) {
            this.time = time;
            this.data = data;
        }
    }

    private static class TimedSentiment {
        private final Instant time;
        private final TTChatGPTSentiment data;

        private TimedSentiment(Instant time, TTChatGPTSentiment data) {
            this.time = time;
            this.data = data;
        }
    }
}
