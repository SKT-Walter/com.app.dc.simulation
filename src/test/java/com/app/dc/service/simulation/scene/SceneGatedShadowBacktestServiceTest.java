package com.app.dc.service.simulation.scene;

import org.junit.Assert;
import org.junit.Test;

public class SceneGatedShadowBacktestServiceTest {

    @Test
    public void shouldSupportEveryTradableSceneFamily() {
        Assert.assertTrue(SceneGatedShadowBacktestService.supportsScene("trend"));
        Assert.assertTrue(SceneGatedShadowBacktestService.supportsScene("range"));
        Assert.assertTrue(SceneGatedShadowBacktestService.supportsScene("channel"));
        Assert.assertTrue(SceneGatedShadowBacktestService.supportsScene("breakout"));
        Assert.assertTrue(SceneGatedShadowBacktestService.supportsScene("reversal"));
        Assert.assertFalse(SceneGatedShadowBacktestService.supportsScene("no_trade"));
    }
}
