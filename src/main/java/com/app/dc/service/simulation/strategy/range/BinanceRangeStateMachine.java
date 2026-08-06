package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import com.app.dc.service.simulation.strategy.profile.SymbolStrategyProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.app.dc.service.simulation.strategy.range.BinanceRangeStateSnapshot.BarMetrics;
import static com.app.dc.service.simulation.strategy.range.BinanceRangeStateSnapshot.ReferenceBox;

/**
 * Stateful range setup. Updating the same bar is idempotent so scoring and signal
 * execution consume exactly the same snapshot.
 */
@Service
public class BinanceRangeStateMachine {
    private final Map<String, BinanceRangeState> states =
            new ConcurrentHashMap<String, BinanceRangeState>();
    @Autowired private SymbolStrategyProfileService profiles;

    public BinanceRangeStateSnapshot evaluate(String symbol, String timeframe,
                                              BarSeries series) {
        BinanceRangeSettings settings = profiles.binanceRangeSettings(
                symbol, timeframe, "binanceRange");
        if (!settings.stateMachineEnabled) {
            BinanceRangeSetupAnalyzer.Snapshot legacy =
                    BinanceRangeSetupAnalyzer.analyze(series,
                            settings.minimumTriggerRangeAtr,
                            settings.minimumBuyRecoveryBodyAtr,
                            settings.minimumBuyCloseLocation);
            return legacy(legacy);
        }
        String key = key(symbol, timeframe);
        BinanceRangeState state = states.computeIfAbsent(
                key, ignored -> new BinanceRangeState());
        synchronized (state) {
            return update(state, series, settings);
        }
    }

    public BinanceRangeStateSnapshot update(BinanceRangeState state,
                                            BarSeries series,
                                            BinanceRangeSettings settings) {
        if (series == null || series.getBarCount() < minimumBars(settings)) {
            return BinanceRangeStateSnapshot.hold("IDLE", 0,
                    "RANGE_DATA_WARMUP", null, null);
        }
        int end = series.getEndIndex();
        if (state.lastProcessedIndex == end && state.cached != null)
            return state.cached;
        if (state.lastProcessedIndex >= 0 && end != state.lastProcessedIndex + 1)
            state.clearSetup();
        state.lastProcessedIndex = end;

        if (state.consumed) state.clearSetup();
        ReferenceBox currentBox = reference(series, end - 1, settings);
        BarMetrics bar = metrics(series, end, currentBox.atr);
        if ("CONFIRMED".equals(state.phase))
            return cache(state, confirmed(state, currentBox, bar, end, settings));
        if ("RECLAIMED".equals(state.phase))
            return cache(state, confirm(state, currentBox, bar, end, settings));
        if ("TOUCHED".equals(state.phase))
            return cache(state, reclaim(state, currentBox, bar, end, settings));
        if (!currentBox.valid) {
            state.clearSetup();
            return cache(state, BinanceRangeStateSnapshot.hold(
                    "IDLE", 0, currentBox.reason, currentBox, bar));
        }

        state.phase = "STABLE";
        state.boxHigh = currentBox.high;
        state.boxLow = currentBox.low;
        state.boxAtr = currentBox.atr;
        state.boxQuality = currentBox.quality;
        double range = currentBox.high - currentBox.low;
        double zone = range * settings.edgeZoneRatio;
        boolean lower = bar.low <= currentBox.low + zone;
        boolean upper = bar.high >= currentBox.high - zone;
        boolean lowerInvalid = bar.low < currentBox.low
                - settings.maximumBreakoutAtr * currentBox.atr;
        boolean upperInvalid = bar.high > currentBox.high
                + settings.maximumBreakoutAtr * currentBox.atr;
        if (lowerInvalid || upperInvalid || (lower && upper)) {
            return cache(state, BinanceRangeStateSnapshot.hold(
                    "STABLE", stableReadiness(currentBox),
                    "RANGE_BREAKOUT_INVALIDATED", currentBox, bar));
        }
        if (lower || upper) {
            state.phase = "TOUCHED";
            state.side = lower ? "BUY" : "SELL";
            state.touchIndex = end;
            state.touchExtreme = lower ? bar.low : bar.high;
            if (!settings.nextBarConfirmationRequired
                    && recovered(state, currentBox, bar, settings))
                return cache(state, confirmReclaimImmediately(
                        state, currentBox, bar, end, settings));
            return cache(state, BinanceRangeStateSnapshot.hold(
                    state.phase, .82, lower ? "RANGE_LOWER_EDGE_TOUCHED"
                            : "RANGE_UPPER_EDGE_TOUCHED", currentBox, bar));
        }
        return cache(state, BinanceRangeStateSnapshot.hold(
                "STABLE", stableReadiness(currentBox),
                "RANGE_STABLE_WAITING_FOR_TOUCH", currentBox, bar));
    }

    public void consume(String symbol, String timeframe, int barIndex) {
        BinanceRangeState state = states.get(key(symbol, timeframe));
        if (state == null) return;
        synchronized (state) {
            if (state.lastProcessedIndex == barIndex) state.consumed = true;
        }
    }

    public void reset(String symbol) {
        String prefix = (symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT)) + "|";
        for (String key : states.keySet())
            if (key.startsWith(prefix)) states.remove(key);
    }

    public BinanceRangeState newState() {
        return new BinanceRangeState();
    }

    private BinanceRangeStateSnapshot reclaim(BinanceRangeState state,
                                              ReferenceBox currentBox,
                                              BarMetrics bar, int end,
                                              BinanceRangeSettings settings) {
        ReferenceBox setupBox = savedBox(state, currentBox);
        if (invalidated(state, setupBox, bar, settings))
            return invalidate(state, currentBox, bar, "RANGE_TOUCH_INVALIDATED");
        if (end - state.touchIndex > settings.touchValidityBars)
            return invalidate(state, currentBox, bar, "RANGE_TOUCH_EXPIRED");
        if (!recovered(state, setupBox, bar, settings))
            return BinanceRangeStateSnapshot.hold("TOUCHED", .82,
                    "RANGE_WAITING_FOR_RECLAIM", setupBox, bar);
        state.phase = "RECLAIMED";
        state.reclaimedIndex = end;
        state.reclaimedClose = bar.close;
        state.reclaimedExtreme = "BUY".equals(state.side) ? bar.low : bar.high;
        if (!settings.nextBarConfirmationRequired)
            return confirmReclaimImmediately(
                    state, setupBox, bar, end, settings);
        return BinanceRangeStateSnapshot.hold("RECLAIMED", .92,
                "RANGE_EDGE_RECLAIMED", setupBox, bar);
    }

    private boolean recovered(BinanceRangeState state, ReferenceBox setupBox,
                              BarMetrics bar, BinanceRangeSettings settings) {
        double range = setupBox.high - setupBox.low;
        boolean buy = "BUY".equals(state.side);
        double requiredBody = buy ? settings.minimumBuyRecoveryBodyAtr : .08;
        double requiredLocation = buy ? settings.minimumBuyCloseLocation : .65;
        return buy
                ? bar.close >= setupBox.low + range * .08
                    && bar.close <= setupBox.low + range * .35
                    && bar.close > bar.open && bar.close > bar.previousClose
                    && bar.low >= state.touchExtreme
                    && bar.closeLocation >= requiredLocation
                    && bar.bodyAtr >= requiredBody
                : bar.close <= setupBox.high - range * .08
                    && bar.close >= setupBox.high - range * .35
                    && bar.close < bar.open && bar.close < bar.previousClose
                    && bar.high <= state.touchExtreme
                    && bar.closeLocation <= 1 - requiredLocation
                    && bar.bodyAtr >= requiredBody;
    }

    private BinanceRangeStateSnapshot confirmReclaimImmediately(
            BinanceRangeState state, ReferenceBox setupBox, BarMetrics bar,
            int end, BinanceRangeSettings settings) {
        state.phase = "CONFIRMED";
        state.reclaimedIndex = end;
        state.reclaimedClose = bar.close;
        state.reclaimedExtreme = "BUY".equals(state.side) ? bar.low : bar.high;
        state.confirmedIndex = end;
        return confirmed(state, setupBox, bar, end, settings);
    }

    private BinanceRangeStateSnapshot confirm(BinanceRangeState state,
                                              ReferenceBox currentBox,
                                              BarMetrics bar, int end,
                                              BinanceRangeSettings settings) {
        ReferenceBox setupBox = savedBox(state, currentBox);
        if (invalidated(state, setupBox, bar, settings))
            return invalidate(state, currentBox, bar, "RANGE_RECLAIM_INVALIDATED");
        if (end != state.reclaimedIndex + 1)
            return invalidate(state, currentBox, bar, "RANGE_CONFIRMATION_EXPIRED");
        boolean buy = "BUY".equals(state.side);
        boolean continuation = buy
                ? bar.close > state.reclaimedClose
                    && bar.low >= state.reclaimedExtreme
                    && bar.close > bar.open && bar.bodyAtr >= .08
                : bar.close < state.reclaimedClose
                    && bar.high <= state.reclaimedExtreme
                    && bar.close < bar.open && bar.bodyAtr >= .08;
        if (!continuation)
            return invalidate(state, currentBox, bar,
                    "RANGE_CONFIRMATION_NOT_CONTINUED");
        state.phase = "CONFIRMED";
        state.confirmedIndex = end;
        return confirmed(state, currentBox, bar, end, settings);
    }

    private BinanceRangeStateSnapshot confirmed(BinanceRangeState state,
                                                ReferenceBox currentBox,
                                                BarMetrics bar, int end,
                                                BinanceRangeSettings settings) {
        ReferenceBox setupBox = savedBox(state, currentBox);
        if (end - state.confirmedIndex >= settings.confirmedValidityBars)
            return invalidate(state, currentBox, bar, "RANGE_CONFIRMATION_EXPIRED");
        if (invalidated(state, setupBox, bar, settings))
            return invalidate(state, currentBox, bar, "RANGE_CONFIRMATION_INVALIDATED");
        double mid = (setupBox.high + setupBox.low) / 2;
        double range = setupBox.high - setupBox.low;
        boolean buy = "BUY".equals(state.side);
        double take = buy ? mid + range * settings.targetExtensionRatio
                : mid - range * settings.targetExtensionRatio;
        if ((buy && bar.close >= take) || (!buy && bar.close <= take))
            return invalidate(state, currentBox, bar, "RANGE_TARGET_ALREADY_REACHED");
        double stop = buy
                ? Math.min(state.touchExtreme, setupBox.low)
                    - settings.stopPaddingAtr * setupBox.atr
                : Math.max(state.touchExtreme, setupBox.high)
                    + settings.stopPaddingAtr * setupBox.atr;
        double risk = buy ? bar.close - stop : stop - bar.close;
        double reward = buy ? take - bar.close : bar.close - take;
        double rewardRisk = reward / Math.max(risk, 1e-9);
        if (!Double.isFinite(rewardRisk) || rewardRisk < settings.minimumRewardRisk)
            return invalidate(state, currentBox, bar,
                    "RANGE_REWARD_RISK_REJECTED");
        return new BinanceRangeStateSnapshot("CONFIRMED", state.side, 1,
                "RANGE_REVERSAL_CONFIRMED", setupBox.high, setupBox.low,
                stop, take, rewardRisk, bar.rangeAtr, bar.bodyAtr,
                bar.closeLocation, setupBox.rangeAtr, setupBox.quality);
    }

    private boolean invalidated(BinanceRangeState state, ReferenceBox box,
                                BarMetrics bar, BinanceRangeSettings settings) {
        return bar.close < box.low || bar.close > box.high
                || bar.low < box.low - settings.maximumBreakoutAtr * box.atr
                || bar.high > box.high + settings.maximumBreakoutAtr * box.atr;
    }

    private BinanceRangeStateSnapshot invalidate(BinanceRangeState state,
                                                 ReferenceBox currentBox,
                                                 BarMetrics bar, String reason) {
        state.clearSetup();
        state.phase = "STABLE";
        state.boxHigh = currentBox.high;
        state.boxLow = currentBox.low;
        state.boxAtr = currentBox.atr;
        state.boxQuality = currentBox.quality;
        return BinanceRangeStateSnapshot.hold("STABLE",
                stableReadiness(currentBox), reason, currentBox, bar);
    }

    private ReferenceBox reference(BarSeries series, int referenceEnd,
                                   BinanceRangeSettings settings) {
        double currentHigh = BinanceStrategyMath.highestHigh(
                series, referenceEnd, settings.lookbackBars);
        double currentLow = BinanceStrategyMath.lowestLow(
                series, referenceEnd, settings.lookbackBars);
        double currentRange = currentHigh - currentLow;
        double atr = BinanceStrategyMath.atr(series, referenceEnd, 14);
        if (!positive(currentRange) || !positive(atr))
            return new ReferenceBox(false, "RANGE_INVALID_REFERENCE",
                    currentHigh, currentLow, atr, 0, 0);
        double rangeAtr = currentRange / atr;
        if (rangeAtr < settings.minimumBoxRangeAtr
                || rangeAtr > settings.maximumBoxRangeAtr)
            return new ReferenceBox(false, "RANGE_BOX_ATR_MISMATCH",
                    currentHigh, currentLow, atr, rangeAtr, 0);
        double quality = 1;
        for (int offset = 0; offset < settings.stabilityBars; offset++) {
            int end = referenceEnd - offset;
            double high = BinanceStrategyMath.highestHigh(
                    series, end, settings.lookbackBars);
            double low = BinanceStrategyMath.lowestLow(
                    series, end, settings.lookbackBars);
            double range = high - low;
            double windowAtr = BinanceStrategyMath.atr(series, end, 14);
            double windowRangeAtr = range / Math.max(windowAtr, 1e-9);
            double smaNow = BinanceStrategyMath.sma(series, end, settings.lookbackBars);
            double smaPrevious = BinanceStrategyMath.sma(
                    series, end - 1, settings.lookbackBars);
            double slope = smaPrevious == 0 ? Double.POSITIVE_INFINITY
                    : Math.abs((smaNow - smaPrevious) / smaPrevious);
            if (!positive(range) || BinanceStrategyMath.bandWidthPct(high, low) > .05
                    || slope > .0015
                    || windowRangeAtr < settings.minimumBoxRangeAtr
                    || windowRangeAtr > settings.maximumBoxRangeAtr
                    || midCrosses(series, end, (high + low) / 2,
                            settings.lookbackBars) < settings.minimumMidCrosses)
                return new ReferenceBox(false, "RANGE_BOX_UNSTABLE",
                        currentHigh, currentLow, atr, rangeAtr, 0);
            double centerShift = Math.abs((high + low - currentHigh - currentLow) / 2)
                    / currentRange;
            double widthChange = Math.abs(range - currentRange) / currentRange;
            if (centerShift > .15 || widthChange > .25)
                return new ReferenceBox(false, "RANGE_BOUNDARY_DRIFT",
                        currentHigh, currentLow, atr, rangeAtr, 0);
            quality = Math.min(quality, clamp(1 - slope / .0015));
        }
        double center = (settings.minimumBoxRangeAtr + settings.maximumBoxRangeAtr) / 2;
        double halfWidth = Math.max(.1,
                (settings.maximumBoxRangeAtr - settings.minimumBoxRangeAtr) / 2);
        double atrFit = clamp(1 - Math.abs(rangeAtr - center) / halfWidth);
        quality = .6 * quality + .4 * atrFit;
        return new ReferenceBox(true, "RANGE_BOX_STABLE",
                currentHigh, currentLow, atr, rangeAtr, quality);
    }

    private BarMetrics metrics(BarSeries series, int end, double atr) {
        Bar current = series.getBar(end);
        double open = current.getOpenPrice().doubleValue();
        double high = current.getHighPrice().doubleValue();
        double low = current.getLowPrice().doubleValue();
        double close = current.getClosePrice().doubleValue();
        double previous = series.getBar(end - 1).getClosePrice().doubleValue();
        double range = Math.max(high - low, 1e-9);
        return new BarMetrics(open, high, low, close, previous,
                range / Math.max(atr, 1e-9),
                Math.abs(close - open) / Math.max(atr, 1e-9),
                clamp((close - low) / range));
    }

    private ReferenceBox savedBox(BinanceRangeState state,
                                  ReferenceBox current) {
        return new ReferenceBox(true, "RANGE_SAVED_BOX",
                state.boxHigh, state.boxLow, state.boxAtr,
                (state.boxHigh - state.boxLow) / Math.max(state.boxAtr, 1e-9),
                state.boxQuality);
    }

    private int midCrosses(BarSeries series, int end, double mid, int lookback) {
        int start = Math.max(1, end - lookback + 1);
        int crosses = 0;
        double previous = series.getBar(start - 1).getClosePrice().doubleValue() - mid;
        for (int i = start; i <= end; i++) {
            double current = series.getBar(i).getClosePrice().doubleValue() - mid;
            if ((previous < 0 && current >= 0)
                    || (previous > 0 && current <= 0)) crosses++;
            previous = current;
        }
        return crosses;
    }

    private BinanceRangeStateSnapshot legacy(BinanceRangeSetupAnalyzer.Snapshot value) {
        return new BinanceRangeStateSnapshot("LEGACY", value.side,
                value.readiness, value.reason, value.high, value.low,
                value.stopPrice, value.takePrice, value.rewardRisk,
                value.triggerRangeAtr, value.bodyAtr, value.closeLocation,
                Double.NaN, value.stable ? .5 : 0);
    }

    private BinanceRangeStateSnapshot cache(BinanceRangeState state,
                                            BinanceRangeStateSnapshot value) {
        state.cached = value;
        return value;
    }

    private double stableReadiness(ReferenceBox box) {
        return .70 + .10 * box.quality;
    }

    private int minimumBars(BinanceRangeSettings settings) {
        return settings.lookbackBars + settings.stabilityBars + 2;
    }

    private String key(String symbol, String timeframe) {
        return (symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT))
                + "|" + (timeframe == null ? ""
                : timeframe.trim().toUpperCase(Locale.ROOT));
    }

    private boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, Double.isFinite(value) ? value : 0));
    }
}
