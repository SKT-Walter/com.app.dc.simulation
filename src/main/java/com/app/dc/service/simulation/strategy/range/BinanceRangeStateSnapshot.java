package com.app.dc.service.simulation.strategy.range;

/** Read-only result shared by the deterministic scorer and executable strategy. */
public final class BinanceRangeStateSnapshot {
    public final String phase;
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
    public final double boxRangeAtr;
    public final double boxQuality;

    public BinanceRangeStateSnapshot(String phase, String side, double readiness,
                                     String reason, double high, double low,
                                     double stopPrice, double takePrice,
                                     double rewardRisk, double triggerRangeAtr,
                                     double bodyAtr, double closeLocation,
                                     double boxRangeAtr, double boxQuality) {
        this.phase = phase;
        this.side = side;
        this.readiness = clamp(readiness);
        this.reason = reason;
        this.high = high;
        this.low = low;
        this.stopPrice = stopPrice;
        this.takePrice = takePrice;
        this.rewardRisk = rewardRisk;
        this.triggerRangeAtr = triggerRangeAtr;
        this.bodyAtr = bodyAtr;
        this.closeLocation = closeLocation;
        this.boxRangeAtr = boxRangeAtr;
        this.boxQuality = clamp(boxQuality);
    }

    public boolean actionable() {
        return "BUY".equals(side) || "SELL".equals(side);
    }

    static BinanceRangeStateSnapshot hold(String phase, double readiness,
                                          String reason, ReferenceBox box,
                                          BarMetrics bar) {
        return new BinanceRangeStateSnapshot(phase, "HOLD", readiness, reason,
                box == null ? Double.NaN : box.high,
                box == null ? Double.NaN : box.low,
                Double.NaN, Double.NaN, 0,
                bar == null ? Double.NaN : bar.rangeAtr,
                bar == null ? Double.NaN : bar.bodyAtr,
                bar == null ? Double.NaN : bar.closeLocation,
                box == null ? Double.NaN : box.rangeAtr,
                box == null ? 0 : box.quality);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, Double.isFinite(value) ? value : 0));
    }

    static final class ReferenceBox {
        final boolean valid;
        final String reason;
        final double high;
        final double low;
        final double atr;
        final double rangeAtr;
        final double quality;

        ReferenceBox(boolean valid, String reason, double high, double low,
                     double atr, double rangeAtr, double quality) {
            this.valid = valid;
            this.reason = reason;
            this.high = high;
            this.low = low;
            this.atr = atr;
            this.rangeAtr = rangeAtr;
            this.quality = quality;
        }
    }

    static final class BarMetrics {
        final double open;
        final double high;
        final double low;
        final double close;
        final double previousClose;
        final double rangeAtr;
        final double bodyAtr;
        final double closeLocation;

        BarMetrics(double open, double high, double low, double close,
                   double previousClose, double rangeAtr, double bodyAtr,
                   double closeLocation) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.previousClose = previousClose;
            this.rangeAtr = rangeAtr;
            this.bodyAtr = bodyAtr;
            this.closeLocation = closeLocation;
        }
    }
}
