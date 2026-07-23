package com.app.dc.simulation;

import com.app.dc.po.backtest.BacktestParam;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import java.util.Map;

public class LocalBacktestRunnerTest {
    @Test
    public void parsesSupportedCommandLineArguments() {
        Map<String, String> o = LocalBacktestRunner.parse(new String[]{"--strategy=binanceTrend", "--symbols=ETHUSDT,BTCUSDT", "--text=15m", "--begin=2026-06-01", "--end=2026-06-30", "--chunkDays=3"});
        BacktestParam p = LocalBacktestRunner.toParam(o);
        Assert.assertEquals("binanceTrend", p.strategyName);
        Assert.assertEquals("ETHUSDT,BTCUSDT", p.symbols);
        Assert.assertEquals("2026-06-30", p.endDate);
    }

    /**
     * IDE中可直接右键运行本方法。修改下面参数即可切换策略、品种和回测区间；
     * config/data/ETHUSDT.json存在时使用本地文件，否则自动查询ClickHouse。
     */
    @Test
    @Ignore("manual ClickHouse integration backtest; run from IDE main instead")
    public void runLocalBacktest() throws Exception {
        LocalBacktestRunner.main(new String[]{
                "--strategy=all",
                "--symbols=ETHUSDT",
                "--text=15m",
                "--begin=2026-06-01",
                "--end=2026-06-30",
                "--capital=10000",
                "--fee=0.04",
                "--stopLoss=6",
                "--takeProfit=6",
                "--maxHoldBars=0",
                "--chunkDays=2",
                "--ignoreSentimentGuard=true"
        });
    }

    /** IDE中可直接运行本类的 main，执行确定性动态链路；不参与 mvn test。 */
    public static void main(String[] args) throws Exception {
        LocalBacktestRunner.main(new String[]{
                "--strategy=deterministic",
                "--symbols=ETHUSDT",
                "--text=15m",
                "--begin=2025-05-01",
                "--end=2025-12-30",
                "--chunkDays=2"
        });
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReverseDateRange() {
        LocalBacktestRunner.toParam(LocalBacktestRunner.parse(new String[]{"--begin=2026-02-01", "--end=2026-01-01"}));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownArgumentShape() {
        LocalBacktestRunner.parse(new String[]{"strategy=all"});
    }
}
