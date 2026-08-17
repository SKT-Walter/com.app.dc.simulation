package com.app.dc.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.strategy.core.deterministic.DeterministicScoreCard;
import com.app.dc.strategy.core.deterministic.DeterministicScoringService;
import com.app.dc.strategy.core.deterministic.CandidateRuleChain;
import com.app.dc.strategy.core.deterministic.CandidateSelectionResult;
import com.app.dc.strategy.core.deterministic.MarketContextFactory;
import com.app.dc.strategy.core.deterministic.StrategyEvaluationContext;
import com.app.dc.strategy.core.deterministic.StructuralTrendSnapshot;
import com.app.dc.strategy.core.dynamic.MarketRegime;
import com.app.dc.strategy.core.dynamic.DynamicStrategyCatalog;
import com.app.dc.strategy.core.dynamic.DynamicStrategyMeta;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;

public class DeterministicScoringCoverageTest {
    @Test
    public void everyEnabledCatalogStrategyHasFiniteDeterministicScore() throws Exception {
        Path project = LocalBacktestRunner.resolveProjectDir(Collections.<String, String>emptyMap());
        ConfigurableApplicationContext spring = new SpringApplicationBuilder(LocalBacktestRunner.LocalApp.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.location=" + project.resolve("config/application.properties").toUri())
                .run(LocalBacktestRunner.springArguments(project, "TEST.DETERMINISTIC.SCORING"));
        try {
            DynamicStrategyCatalog catalog = spring.getBean(DynamicStrategyCatalog.class);
            DeterministicScoringService scoring = spring.getBean(DeterministicScoringService.class);
            MarketContextFactory contexts = spring.getBean(MarketContextFactory.class);
            CandidateRuleChain candidateRules = spring.getBean(CandidateRuleChain.class);
            BarSeries series = series();
            MarketRegime regime = regime();
            StrategyEvaluationContext context = contexts.create(
                    "ETHUSDT", "15M", series, new TTbookOhlc(), regime);
            int enabled = 0;
            for (DynamicStrategyMeta meta : catalog.all()) {
                if (!meta.enabled) continue;
                enabled++;
                DeterministicScoreCard card = scoring.score(context, meta);
                Assert.assertEquals(meta.strategyName, card.strategyName);
                Assert.assertTrue(meta.strategyName, Double.isFinite(card.score));
                Assert.assertTrue(meta.strategyName, card.score >= 0 && card.score <= 100);
            }
            Assert.assertEquals(76, enabled);

            MarketRegime range = rangeRegime();
            CandidateSelectionResult ethCandidates = candidateRules.select(contexts.create(
                    "ETHUSDT", "15M", series, new TTbookOhlc(), range));
            Assert.assertTrue(ethCandidates.candidates.stream()
                    .anyMatch(meta -> "binanceRangeETH".equals(meta.strategyName)));
            Assert.assertFalse(ethCandidates.candidates.stream()
                    .anyMatch(meta -> "binanceRangeSOL".equals(meta.strategyName)
                            || "binanceRange".equals(meta.strategyName)));
            CandidateSelectionResult solCandidates = candidateRules.select(contexts.create(
                    "SOLUSDT", "15M", series, new TTbookOhlc(), range));
            Assert.assertTrue(solCandidates.candidates.stream()
                    .anyMatch(meta -> "binanceRangeSOL".equals(meta.strategyName)));
            Assert.assertFalse(solCandidates.candidates.stream()
                    .anyMatch(meta -> "binanceRangeETH".equals(meta.strategyName)
                            || "binanceRange".equals(meta.strategyName)));
            CandidateSelectionResult btcCandidates = candidateRules.select(contexts.create(
                    "BTCUSDT", "15M", series, new TTbookOhlc(), regime));
            Assert.assertTrue(btcCandidates.candidates.stream()
                    .anyMatch(meta -> "btcStructuralBullTrendBTC".equals(meta.strategyName)));
            Assert.assertTrue(btcCandidates.rejectedStrategies.containsKey("btcBullLaunchTrendBTC"));
            Assert.assertTrue(btcCandidates.rejectedStrategies.containsKey("btcStructuralBearTrendBTC"));
            Assert.assertFalse(btcCandidates.candidates.stream()
                    .anyMatch(meta -> "btcStructuralBullTrend".equals(meta.strategyName)));
            Assert.assertEquals("SYMBOL_NOT_SUPPORTED",
                    btcCandidates.rejectedStrategies.get("ethStructuralBullTrendETH"));
            Assert.assertEquals("SYMBOL_NOT_SUPPORTED",
                    btcCandidates.rejectedStrategies.get("solMomentumBullTrendSOL"));
            Assert.assertEquals("SYMBOL_NOT_SUPPORTED",
                    btcCandidates.rejectedStrategies.get("solBullLaunchTrendSOL"));

            StructuralTrendSnapshot bear = new StructuralTrendSnapshot(
                    StructuralTrendSnapshot.BEAR, StructuralTrendSnapshot.BEAR,
                    "ESTABLISHED", .90, 20, 0, true);
            CandidateSelectionResult ethBearCandidates = candidateRules.select(contexts.create(
                    "ETHUSDT", "15M", series, new TTbookOhlc(), regime, bear));
            Assert.assertEquals("STRUCTURAL_DIRECTION_CONFLICT",
                    ethBearCandidates.rejectedStrategies.get("emaPullbackBuyETH"));
            CandidateSelectionResult solBearCandidates = candidateRules.select(contexts.create(
                    "SOLUSDT", "15M", series, new TTbookOhlc(), regime, bear));
            Assert.assertNull(solBearCandidates.rejectedStrategies.get("emaPullbackBuySOL"));
        } finally {
            spring.close();
        }
    }

    private BarSeries series() {
        BarSeries series = new BaseBarSeries("deterministic-score");
        ZonedDateTime start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault());
        for (int i = 0; i < 100; i++) {
            double close = 1000 + i * .8 + Math.sin(i / 3d) * 4;
            series.addBar(new BaseBar(Duration.ofMinutes(15), start.plusMinutes(i * 15L),
                    BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close + 3),
                    BigDecimal.valueOf(close - 3), BigDecimal.valueOf(close),
                    BigDecimal.valueOf(1000 + i * 10)));
        }
        return series;
    }

    private MarketRegime regime() {
        MarketRegime regime = new MarketRegime();
        regime.trend = "UP";
        regime.volatility = "HIGH";
        regime.confidence = .82;
        regime.tradeable = true;
        regime.breakoutExpansion = true;
        regime.barTime = 1;
        regime.features.put("atr", 8d);
        regime.features.put("atrPercentile", .8);
        regime.features.put("adx", 35d);
        regime.features.put("emaSlowSlope", .002d);
        regime.features.put("bollingerBandwidth", .04d);
        regime.features.put("volumeRatio", 1.3d);
        return regime;
    }

    private MarketRegime rangeRegime() {
        MarketRegime regime = regime();
        regime.trend = "NONE";
        regime.volatility = "LOW";
        regime.breakoutExpansion = false;
        return regime;
    }
}
