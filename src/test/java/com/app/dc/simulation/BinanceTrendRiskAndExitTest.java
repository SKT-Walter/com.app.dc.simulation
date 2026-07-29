package com.app.dc.simulation;

import com.app.dc.po.Side;
import com.app.dc.po.Signal;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.BacktestModels.Position;
import com.app.dc.service.simulation.deterministic.StructuralTrendSnapshot;
import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.strategy.exit.BinanceTrendPositionExitPolicy;
import com.app.dc.service.simulation.strategy.exit.PositionExitDecision;
import com.app.dc.service.simulation.strategy.exit.StrategyPositionExitContext;
import com.app.dc.service.simulation.strategy.risk.BinanceTrendEntryRiskService;
import com.app.dc.service.simulation.strategy.trend.BinanceTrendBacktestStrategy;
import org.junit.Assert;
import org.junit.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class BinanceTrendRiskAndExitTest {

    @Test
    public void counterStructureTrendSignalUsesAtrInitialStopOnLossSide() throws Exception {
        BinanceTrendBacktestStrategy strategy = new BinanceTrendBacktestStrategy();
        BarSeries series = decliningSeries(64, 150.0, 0.08);
        double close = series.getLastBar().getClosePrice().doubleValue();
        Signal signal = strategy.evaluate("ETHUSDT", "15m", series, ohlc(close));

        BinanceTrendEntryRiskService risk = new BinanceTrendEntryRiskService();
        setField(risk, "initialStopAtrMultiplier", 2.0);
        setField(risk, "minimumInitialStopPct", 0.03);
        setField(risk, "maximumInitialStopPct", 0.04);
        boolean applied = risk.apply("binanceTrend", signal, series,
                new StructuralTrendSnapshot(StructuralTrendSnapshot.BULL,
                        StructuralTrendSnapshot.BULL, "TREND", .80, 10, 0, true));

        Assert.assertTrue(applied);
        Assert.assertEquals(Side.SELL, signal.side);
        Assert.assertNotNull(signal.stopPrice);
        Assert.assertTrue(signal.stopPrice.doubleValue() > close);
        Assert.assertTrue(signal.stopPrice.doubleValue() - close >= close * 0.03 - 0.000001);
        Assert.assertTrue(signal.stopPrice.doubleValue() - close <= close * 0.04 + 0.000001);
    }

    @Test
    public void alignedTrendSignalKeepsSharedFallbackStop() throws Exception {
        BinanceTrendBacktestStrategy strategy = new BinanceTrendBacktestStrategy();
        BarSeries series = decliningSeries(64, 150.0, 0.08);
        Signal signal = strategy.evaluate("ETHUSDT", "15m", series,
                ohlc(series.getLastBar().getClosePrice().doubleValue()));
        BinanceTrendEntryRiskService risk = new BinanceTrendEntryRiskService();
        setField(risk, "initialStopAtrMultiplier", 2.0);
        setField(risk, "minimumInitialStopPct", 0.03);
        setField(risk, "maximumInitialStopPct", 0.04);

        boolean applied = risk.apply("binanceTrend", signal, series,
                new StructuralTrendSnapshot(StructuralTrendSnapshot.BEAR,
                        StructuralTrendSnapshot.BEAR, "TREND", .80, 10, 0, true));

        Assert.assertFalse(applied);
        Assert.assertNull(signal.stopPrice);
    }

    @Test
    public void shortDoesNotExitOnOppositeRegimeWhileMasRemainTrendAligned() throws Exception {
        BinanceTrendPositionExitPolicy policy = policy();
        Position position = shortPosition();
        BarSeries series = decliningSeries(60, 150.0, 0.5);
        BacktestRegime up = regime("UP");
        StrategyPositionExitContext context = new StrategyPositionExitContext(
                series, up, StructuralTrendSnapshot.warmup());

        Assert.assertFalse(policy.evaluate(position, context).exit);
        Assert.assertFalse(policy.evaluate(position, context).exit);
        Assert.assertFalse(policy.evaluate(position, context).exit);
    }

    @Test
    public void shortDoesNotExitOnMaInvalidationWithoutContextConfirmation() throws Exception {
        BinanceTrendPositionExitPolicy policy = policy();
        Position position = shortPosition();
        BarSeries series = flatSeries(40, 100.0);
        for (int i = 1; i <= 20; i++) {
            double close = 100.0 + i * 0.10;
            add(series, close - 0.05, close + 0.5, close - 0.5, close);
        }
        add(series, 103.0, 104.0, 102.0, 103.0);

        PositionExitDecision decision = policy.evaluate(position,
                new StrategyPositionExitContext(series, regime("DOWN"),
                        StructuralTrendSnapshot.warmup()));

        Assert.assertFalse(decision.exit);
    }

    @Test
    public void shortDoesNotExitWhenStructureAndMaAgreeButRegimeDoesNot() throws Exception {
        BinanceTrendPositionExitPolicy policy = policy();
        Position position = shortPosition();
        BarSeries series = flatSeries(40, 100.0);
        for (int i = 1; i <= 20; i++) {
            double close = 100.0 + i * 0.10;
            add(series, close - 0.05, close + 0.5, close - 0.5, close);
        }
        add(series, 103.0, 104.0, 102.0, 103.0);
        BacktestRegime down = regime("DOWN");

        Assert.assertFalse(policy.evaluate(position, context(series, down, 0.60)).exit);
        Assert.assertFalse(policy.evaluate(position, context(series, down, 0.61)).exit);
        PositionExitDecision decision = policy.evaluate(position, context(series, down, 0.62));

        Assert.assertFalse(decision.exit);
    }

    @Test
    public void shortDoesNotExitWhenRegimeAndMaAgreeButStructureDoesNot() throws Exception {
        BinanceTrendPositionExitPolicy policy = policy();
        Position position = shortPosition();
        BarSeries series = flatSeries(40, 100.0);
        for (int i = 1; i <= 20; i++) {
            double close = 100.0 + i * 0.10;
            add(series, close - 0.05, close + 0.5, close - 0.5, close);
        }
        add(series, 103.0, 104.0, 102.0, 103.0);
        StrategyPositionExitContext context = new StrategyPositionExitContext(
                series, regime("UP"), StructuralTrendSnapshot.warmup());

        Assert.assertFalse(policy.evaluate(position, context).exit);
        Assert.assertFalse(policy.evaluate(position, context).exit);
        PositionExitDecision decision = policy.evaluate(position, context);

        Assert.assertFalse(decision.exit);
    }

    @Test
    public void shortExitsOnlyWhenRegimeMaAndStructureAllAgree() throws Exception {
        BinanceTrendPositionExitPolicy policy = policy();
        Position position = shortPosition();
        BarSeries series = flatSeries(40, 100.0);
        for (int i = 1; i <= 20; i++) {
            double close = 100.0 + i * 0.10;
            add(series, close - 0.05, close + 0.5, close - 0.5, close);
        }
        add(series, 103.0, 104.0, 102.0, 103.0);
        BacktestRegime up = regime("UP");

        Assert.assertFalse(policy.evaluate(position, context(series, up, 0.60)).exit);
        Assert.assertFalse(policy.evaluate(position, context(series, up, 0.61)).exit);
        PositionExitDecision decision = policy.evaluate(position, context(series, up, 0.62));

        Assert.assertTrue(decision.exit);
        Assert.assertEquals(BinanceTrendPositionExitPolicy.CONSENSUS_CONFIRMATION,
                decision.reason);
    }

    private BinanceTrendPositionExitPolicy policy() throws Exception {
        BinanceTrendPositionExitPolicy policy = new BinanceTrendPositionExitPolicy();
        setField(policy, "regimeConfirmationBars", 3);
        setField(policy, "structuralConfirmationBars", 3);
        setField(policy, "structuralMinimumConfidence", 0.60);
        setField(policy, "confidenceIncreaseEpsilon", 0.005);
        return policy;
    }

    private StrategyPositionExitContext context(BarSeries series, BacktestRegime regime,
                                                double confidence) {
        StructuralTrendSnapshot structural = new StructuralTrendSnapshot(
                StructuralTrendSnapshot.BULL, StructuralTrendSnapshot.BULL,
                "TREND", confidence, 10, 0, true);
        return new StrategyPositionExitContext(series, regime, structural);
    }

    private Position shortPosition() {
        Position position = new Position();
        position.side = Side.SELL;
        position.strategyName = "binanceTrend";
        position.entryIndex = 0;
        return position;
    }

    private BacktestRegime regime(String trend) {
        BacktestRegime regime = new BacktestRegime();
        regime.trend = trend;
        regime.tradeable = true;
        return regime;
    }

    private BarSeries decliningSeries(int count, double firstClose, double step) {
        BarSeries series = new BaseBarSeries("declining");
        for (int i = 0; i < count; i++) {
            double close = firstClose - i * step;
            add(series, close + 0.25, close + 0.8, close - 0.8, close);
        }
        return series;
    }

    private BarSeries flatSeries(int count, double close) {
        BarSeries series = new BaseBarSeries("flat");
        for (int i = 0; i < count; i++) add(series, close, close + 0.5, close - 0.5, close);
        return series;
    }

    private void add(BarSeries series, double open, double high, double low, double close) {
        ZonedDateTime time = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0,
                ZoneId.systemDefault()).plusMinutes(series.getBarCount() * 15L);
        series.addBar(new BaseBar(Duration.ofMinutes(15), time,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                BigDecimal.valueOf(close), BigDecimal.valueOf(1000)));
    }

    private TTbookOhlc ohlc(double close) {
        TTbookOhlc value = new TTbookOhlc();
        value.close = BigDecimal.valueOf(close);
        return value;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
