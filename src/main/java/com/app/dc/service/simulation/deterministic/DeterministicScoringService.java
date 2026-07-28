package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.BacktestStrategyService;
import com.app.dc.service.simulation.dynamic.DynamicStrategyCatalog;
import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Combines family market suitability with one pure setup scorer per concrete strategy. */
@Service
public class DeterministicScoringService {
    private final Map<String, StrategySetupScorer> scorers = new HashMap<String, StrategySetupScorer>();
    @Autowired private DynamicStrategyCatalog catalog;
    @Autowired private BacktestStrategyService strategyService;
    @Autowired private StructuralTrendScoreModifier structuralTrendScoreModifier;

    @Autowired
    public DeterministicScoringService(List<StrategySetupScorer> scorerList) {
        for (StrategySetupScorer scorer : scorerList) {
            String key = scorer.strategyName().toLowerCase(Locale.ROOT);
            if (scorers.put(key, scorer) != null)
                throw new IllegalStateException("duplicate deterministic scorer: " + scorer.strategyName());
        }
    }

    @PostConstruct
    public void validateCatalog() {
        for (DynamicStrategyMeta meta : catalog.all()) {
            if (!meta.enabled) continue;
            StrategySetupScorer scorer = scorers.get(meta.strategyName.toLowerCase(Locale.ROOT));
            if (scorer == null) throw new IllegalStateException("missing deterministic scorer: " + meta.strategyName);
            if (!meta.family.equalsIgnoreCase(scorer.family()))
                throw new IllegalStateException("scorer family mismatch: " + meta.strategyName);
            strategyService.getStrategy(meta.strategyName);
        }
    }

    public List<DeterministicScoreCard> score(StrategyEvaluationContext context,
                                               CandidateSelectionResult selection) {
        List<DeterministicScoreCard> result = new ArrayList<DeterministicScoreCard>();
        for (DynamicStrategyMeta candidate : selection.candidates) result.add(score(context, candidate));
        return result;
    }

    public DeterministicScoreCard score(StrategyEvaluationContext context, DynamicStrategyMeta meta) {
        StrategySetupScorer scorer = scorers.get(meta.strategyName.toLowerCase(Locale.ROOT));
        if (scorer == null) throw new IllegalStateException("missing deterministic scorer: " + meta.strategyName);
        StrategySetupScore setup = scorer.score(context);
        DeterministicScoreCard card = new DeterministicScoreCard();
        card.strategyName = meta.strategyName;
        card.family = meta.family;
        card.minimumScore = meta.minimumScore;
        card.setupReadiness = setup.readiness;
        card.supportingFactors.addAll(setup.supportingFactors);
        card.penaltyFactors.addAll(setup.penaltyFactors);
        TechnicalSnapshot t = context.technical;
        if ("TREND".equalsIgnoreCase(meta.family)) trend(context, t, setup, card);
        else if ("MEAN_REVERSION".equalsIgnoreCase(meta.family)) meanReversion(context, t, setup, card);
        else if ("BREAKOUT".equalsIgnoreCase(meta.family)) breakout(context, t, setup, card);
        else throw new IllegalStateException("unsupported strategy family: " + meta.family);
        card.penalty += setup.penalty;
        structuralTrendScoreModifier.apply(context, meta, card);
        card.score = clampScore(sum(card.components) - card.penalty + card.structuralAdjustment);
        return card;
    }

    private void trend(StrategyEvaluationContext x, TechnicalSnapshot t, StrategySetupScore setup,
                       DeterministicScoreCard card) {
        put(card, "regime", 20 * x.regime.confidence); card.regimeScore = card.components.get("regime");
        put(card, "adx", 20 * n(t.adx, 20, 45));
        put(card, "slope", 15 * n(Math.abs(t.emaSlowSlope), .0005, .003));
        put(card, "structure", 15 * t.structureStrength);
        put(card, "setup", 20 * setup.readiness);
        put(card, "volume", 10 * n(t.volumeRatio, .8, 1.5));
        if (t.atr > 0 && Math.abs(t.close - t.ema20) / t.atr > 2) {
            card.penalty += 10; card.penaltyFactors.add("距离均线超过2ATR");
        }
        if (decliningAdxProxy(t)) { card.penalty += 5; card.penaltyFactors.add("趋势强度不足"); }
    }

    private void meanReversion(StrategyEvaluationContext x, TechnicalSnapshot t, StrategySetupScore setup,
                               DeterministicScoreCard card) {
        put(card, "regime", 15 * x.regime.confidence); card.regimeScore = card.components.get("regime");
        put(card, "weakTrend", 15 * (1 - n(t.adx, 15, 30)));
        put(card, "deviation", 20 * n(Math.abs(t.zScore20), .5, 2.5));
        put(card, "setup", 20 * setup.readiness);
        double volatilityFit = "LOW".equals(x.regime.volatility) ? 1
                : "NORMAL".equals(x.regime.volatility) ? .75 : .25;
        put(card, "volatility", 15 * volatilityFit);
        put(card, "meanRecovery", 10 * t.meanRecoveryStrength);
        put(card, "volume", 5 * n(t.volumeRatio, .5, 1.5));
        if (t.adx > 30) { card.penalty += 15; card.penaltyFactors.add("ADX超过30"); }
        if (t.previousBandwidth > 0 && t.bandwidth / t.previousBandwidth > 1.2) {
            card.penalty += 10; card.penaltyFactors.add("波动带宽快速扩张");
        }
        if (Math.abs(t.emaSlowSlope) > .003) { card.penalty += 8; card.penaltyFactors.add("均线斜率过强"); }
    }

    private void breakout(StrategyEvaluationContext x, TechnicalSnapshot t, StrategySetupScore setup,
                          DeterministicScoreCard card) {
        put(card, "regime", 15 * x.regime.confidence); card.regimeScore = card.components.get("regime");
        put(card, "compression", 20 * clamp(1 - t.previousBandwidth / .05));
        double expansion = t.previousBandwidth <= 0 ? 0 : n(t.bandwidth / t.previousBandwidth, 1.1, 2);
        put(card, "expansion", 20 * expansion);
        put(card, "setup", 20 * setup.readiness);
        put(card, "volume", 10 * n(t.volumeRatio, .8, 1.8));
        double closeQuality = "DOWN".equals(x.regime.trend) ? 1 - t.closeLocation
                : "UP".equals(x.regime.trend) ? t.closeLocation
                : Math.max(t.closeLocation, 1 - t.closeLocation);
        put(card, "closeQuality", 10 * closeQuality);
        put(card, "structure", 5 * t.structureStrength);
        if (t.volumeRatio < 1) { card.penalty += 10; card.penaltyFactors.add("突破缺少成交量"); }
        double wickRatio = 1 - clamp(t.bodyAtr / Math.max((t.high - t.low) / Math.max(t.atr, 1e-9), 1e-9));
        if (wickRatio > .6) { card.penalty += 8; card.penaltyFactors.add("影线占比过高"); }
    }

    private boolean decliningAdxProxy(TechnicalSnapshot t) {
        return t.adx < 22 && Math.abs(t.emaSlowSlope) < .001;
    }

    private void put(DeterministicScoreCard card, String name, double value) {
        card.components.put(name, Math.max(0, Double.isFinite(value) ? value : 0));
    }
    private double sum(Map<String, Double> values) { double total = 0; for (Double v : values.values()) total += v; return total; }
    private double n(double value, double min, double max) { return MarketContextFactory.normalize(value, min, max); }
    private double clamp(double value) { return MarketContextFactory.clamp(value); }
    private double clampScore(double value) { return Math.max(0, Math.min(100, Double.isFinite(value) ? value : 0)); }
}
