package com.app.dc.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.deterministic.DeterministicScoreCard;
import com.app.dc.service.simulation.deterministic.DeterministicScoringService;
import com.app.dc.service.simulation.deterministic.MarketContextFactory;
import com.app.dc.service.simulation.deterministic.StrategyEvaluationContext;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.dynamic.DynamicStrategyCatalog;
import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;
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
            BarSeries series = series();
            BacktestRegime regime = regime();
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
            Assert.assertEquals(22, enabled);
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

    private BacktestRegime regime() {
        BacktestRegime regime = new BacktestRegime();
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
}
