package com.app.dc.service.simulation.strategy.range;

import com.app.dc.service.simulation.strategy.BinanceStrategyMath;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

/**
 * Pure range setup analysis shared by routing scores and the executable strategy.
 * Reference boundaries use closed bars before the trigger bar, so a breakout cannot
 * redefine itself as a new range edge.
 */
public final class BinanceRangeSetupAnalyzer {
    public static final int LOOKBACK = 20;
    public static final int STABILITY_BARS = 4;
    public static final double MAX_WIDTH_PCT = .05;
    public static final double MAX_SLOPE_PCT = .0015;
    public static final double EDGE_ZONE_RATIO = .15;
    public static final double RECOVERY_ZONE_RATIO = .20;
    public static final double MAX_BREAKOUT_ATR = .60;
    public static final double MIN_REWARD_RISK = 1.25;
    private static final double MIN_CLOSE_LOCATION = .65;
    private static final int MIN_MID_CROSSES = 2;

    private BinanceRangeSetupAnalyzer() {
    }

    public static Snapshot analyze(BarSeries series) {
        return analyze(series, 0.0);
    }

    public static Snapshot analyze(BarSeries series, double minimumTriggerRangeAtr) {
        return analyze(series, minimumTriggerRangeAtr, 0.0, MIN_CLOSE_LOCATION);
    }

    public static Snapshot analyze(BarSeries series, double minimumTriggerRangeAtr,
                                   double minimumBuyRecoveryBodyAtr,
                                   double minimumBuyCloseLocation) {
        if (series == null || series.getBarCount() < minimumBars()) {
            return Snapshot.rejected("RANGE_DATA_WARMUP");
        }
        int end = series.getEndIndex();
        int referenceEnd = end - 1;
        double high = BinanceStrategyMath.highestHigh(series, referenceEnd, LOOKBACK);
        double low = BinanceStrategyMath.lowestLow(series, referenceEnd, LOOKBACK);
        double range = high - low;
        double mid = (high + low) / 2;
        double atr = BinanceStrategyMath.atr(series, referenceEnd, 14);
        if (!finitePositive(range) || !finitePositive(mid) || !finitePositive(atr)) {
            return Snapshot.rejected("RANGE_INVALID_REFERENCE");
        }

        double stability = stability(series, referenceEnd, high, low, range);
        if (stability <= 0) return Snapshot.rejected("RANGE_BOX_UNSTABLE");

        Bar current = series.getBar(end);
        double open = current.getOpenPrice().doubleValue();
        double barHigh = current.getHighPrice().doubleValue();
        double barLow = current.getLowPrice().doubleValue();
        double close = current.getClosePrice().doubleValue();
        double previousClose = series.getBar(end - 1).getClosePrice().doubleValue();
        double zone = range * EDGE_ZONE_RATIO;
        double lowerDistance = Math.abs(close - low) / Math.max(zone, 1e-9);
        double upperDistance = Math.abs(high - close) / Math.max(zone, 1e-9);
        double edgeReadiness = clamp(1 - Math.min(lowerDistance, upperDistance));

        boolean lowerTouched = barLow <= low + zone;
        boolean upperTouched = barHigh >= high - zone;
        boolean lowerBreakInvalid = barLow < low - MAX_BREAKOUT_ATR * atr;
        boolean upperBreakInvalid = barHigh > high + MAX_BREAKOUT_ATR * atr;
        double barRange = Math.max(barHigh - barLow, 1e-9);
        double triggerRangeAtr = barRange / atr;
        double bodyAtr = Math.abs(close - open) / atr;
        double closeLocation = clamp((close - barLow) / barRange);
        boolean bullishRecovery = lowerTouched && !lowerBreakInvalid
                && close >= low + zone * RECOVERY_ZONE_RATIO
                && close <= low + range * .35
                && close > open && close > previousClose
                && closeLocation >= Math.max(MIN_CLOSE_LOCATION,
                        minimumBuyCloseLocation);
        boolean bearishRecovery = upperTouched && !upperBreakInvalid
                && close <= high - zone * RECOVERY_ZONE_RATIO
                && close >= high - range * .35
                && close < open && close < previousClose
                && closeLocation <= 1 - MIN_CLOSE_LOCATION;
        boolean buyConfirmationTooWeak = bullishRecovery
                && bodyAtr < Math.max(0.0, minimumBuyRecoveryBodyAtr);
        if (buyConfirmationTooWeak) bullishRecovery = false;
        boolean triggerRangeTooSmall = (bullishRecovery || bearishRecovery)
                && triggerRangeAtr < Math.max(0.0, minimumTriggerRangeAtr);
        if (triggerRangeTooSmall) {
            bullishRecovery = false;
            bearishRecovery = false;
        }

        String side = bullishRecovery ? "BUY" : bearishRecovery ? "SELL" : "HOLD";
        double stop;
        double take;
        double rewardRisk;
        if ("BUY".equals(side)) {
            stop = low - range * .10;
            take = mid - zone * .20;
            rewardRisk = (take - close) / Math.max(close - stop, 1e-9);
        } else if ("SELL".equals(side)) {
            stop = high + range * .10;
            take = mid + zone * .20;
            rewardRisk = (close - take) / Math.max(stop - close, 1e-9);
        } else {
            stop = Double.NaN;
            take = Double.NaN;
            rewardRisk = 0;
        }
        if (!"HOLD".equals(side) && (!Double.isFinite(rewardRisk) || rewardRisk < MIN_REWARD_RISK)) {
            side = "HOLD";
        }

        double recoveryReadiness = bullishRecovery || bearishRecovery ? 1 : 0;
        // Passing the multi-window box test is the dominant preparation signal. This lets the
        // router activate before the one-bar recovery trigger arrives, while edge/recovery still
        // determine the final ranking and actual order.
        double readiness = clamp(.75 + .20 * edgeReadiness + .05 * recoveryReadiness);
        String reason;
        if (lowerBreakInvalid || upperBreakInvalid) reason = "RANGE_BREAKOUT_INVALIDATED";
        else if (buyConfirmationTooWeak) reason = "RANGE_BUY_CONFIRMATION_TOO_WEAK";
        else if (triggerRangeTooSmall) reason = "RANGE_TRIGGER_RANGE_TOO_SMALL";
        else if ("HOLD".equals(side) && (lowerTouched || upperTouched)) reason = "RANGE_RECOVERY_NOT_CONFIRMED";
        else if ("HOLD".equals(side)) reason = "RANGE_EDGE_NOT_TOUCHED";
        else reason = "RANGE_REVERSAL_CONFIRMED";
        return new Snapshot(true, side, readiness, reason, high, low, stop, take,
                rewardRisk, triggerRangeAtr, bodyAtr, closeLocation);
    }

    private static double stability(BarSeries series, int referenceEnd,
                                    double currentHigh, double currentLow, double currentRange) {
        double quality = 1;
        for (int offset = 0; offset < STABILITY_BARS; offset++) {
            int end = referenceEnd - offset;
            double high = BinanceStrategyMath.highestHigh(series, end, LOOKBACK);
            double low = BinanceStrategyMath.lowestLow(series, end, LOOKBACK);
            double range = high - low;
            double widthPct = BinanceStrategyMath.bandWidthPct(high, low);
            double smaNow = BinanceStrategyMath.sma(series, end, LOOKBACK);
            double smaPrev = BinanceStrategyMath.sma(series, end - 1, LOOKBACK);
            double slopePct = smaPrev == 0 ? Double.POSITIVE_INFINITY
                    : Math.abs((smaNow - smaPrev) / smaPrev);
            if (!finitePositive(range) || widthPct > MAX_WIDTH_PCT || slopePct > MAX_SLOPE_PCT
                    || midCrosses(series, end, (high + low) / 2) < MIN_MID_CROSSES) {
                return 0;
            }
            double centerShift = Math.abs((high + low - currentHigh - currentLow) / 2)
                    / Math.max(currentRange, 1e-9);
            double widthChange = Math.abs(range - currentRange) / Math.max(currentRange, 1e-9);
            if (centerShift > .20 || widthChange > .35) return 0;
            quality = Math.min(quality, clamp(1 - slopePct / MAX_SLOPE_PCT));
        }
        return Math.max(.35, quality);
    }

    private static int midCrosses(BarSeries series, int end, double mid) {
        int start = Math.max(1, end - LOOKBACK + 1);
        int crosses = 0;
        double previous = series.getBar(start - 1).getClosePrice().doubleValue() - mid;
        for (int i = start; i <= end; i++) {
            double current = series.getBar(i).getClosePrice().doubleValue() - mid;
            if ((previous < 0 && current >= 0) || (previous > 0 && current <= 0)) crosses++;
            previous = current;
        }
        return crosses;
    }

    public static int minimumBars() {
        return LOOKBACK + STABILITY_BARS + 2;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, Double.isFinite(value) ? value : 0));
    }

    public static final class Snapshot {
        public final boolean stable;
        public final String side;
        public final double readiness;
        public final String reason;
        public final double high;
        public final double low;
        public final double stopPrice;
        public final double takePrice;
        public final double rewardRisk;
        public final double triggerRangeAtr;
        public final double bodyAtr;
        public final double closeLocation;

        Snapshot(boolean stable, String side, double readiness, String reason,
                 double high, double low, double stopPrice, double takePrice,
                 double rewardRisk, double triggerRangeAtr, double bodyAtr,
                 double closeLocation) {
            this.stable = stable;
            this.side = side;
            this.readiness = readiness;
            this.reason = reason;
            this.high = high;
            this.low = low;
            this.stopPrice = stopPrice;
            this.takePrice = takePrice;
            this.rewardRisk = rewardRisk;
            this.triggerRangeAtr = triggerRangeAtr;
            this.bodyAtr = bodyAtr;
            this.closeLocation = closeLocation;
        }

        static Snapshot rejected(String reason) {
            return new Snapshot(false, "HOLD", 0, reason,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                    Double.NaN, Double.NaN, Double.NaN);
        }

        public boolean actionable() {
            return "BUY".equals(side) || "SELL".equals(side);
        }
    }
}
