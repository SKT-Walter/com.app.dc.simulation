package com.app.dc.simulation;

import com.app.dc.service.simulation.dynamic.BacktestRegime;
import com.app.dc.service.simulation.dynamic.DynamicStrategyCatalog;
import org.junit.Assert;
import org.junit.Test;

public class DynamicStrategyCatalogTest {
    @Test
    public void loadsConfiguredStrategiesAndFiltersByRegime() throws Exception {
        DynamicStrategyCatalog catalog = new DynamicStrategyCatalog();
        java.lang.reflect.Field file = DynamicStrategyCatalog.class.getDeclaredField("file");
        file.setAccessible(true);
        file.set(catalog, "./config/dynamic_strategy_catalog.json");
        catalog.load();
        BacktestRegime trend = new BacktestRegime();
        trend.trend = "UP";
        trend.volatility = "NORMAL";
        trend.tradeable = true;
        trend.features.put("adx", 30d);
        trend.features.put("emaSlowSlope", .003d);
        Assert.assertFalse(catalog.candidates(trend).isEmpty());
        Assert.assertTrue(catalog.candidates(trend).stream().allMatch(m -> "TREND".equals(m.family)));
    }
}
