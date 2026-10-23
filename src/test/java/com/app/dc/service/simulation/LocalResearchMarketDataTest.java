package com.app.dc.service.simulation;

import com.app.dc.po.TTbookOhlc;
import com.app.dc.service.simulation.scene.DeepSeekSceneTimelineService;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

public class LocalResearchMarketDataTest {
    private static final String BAR = "2025-08-12 00:00:00.000000\t1\t2\t0.5\t1.5\t100\n";
    private static final String SCENE =
            "2025-08-12 00:00:00\trange\tSUCCESS\thistorical_kline_scene\tmarket_scene_v3_historical_kline_v1\n"
            + "2025-08-12 12:00:00\ttrend\tSUCCESS\thistorical_kline_scene\tmarket_scene_v3_historical_kline_v1\n";

    @Test
    public void installsHashCheckedLocalDataAndExcludesFutureScenes() throws Exception {
        Path dir = Files.createTempDirectory("local-research-market-");
        Files.write(dir.resolve("BTCUSDT-15m.tsv"), BAR.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("BTCUSDT-scenes.tsv"), SCENE.getBytes(StandardCharsets.UTF_8));
        BacktestService service = new BacktestService();
        LocalResearchMarketData.install(service, dir, "BTCUSDT", "15m",
                "2025-08-12", "2025-08-12", hash(BAR), hash(SCENE));

        Field barsField = BacktestService.class.getDeclaredField("queryService");
        barsField.setAccessible(true);
        BacktestQueryService bars = (BacktestQueryService) barsField.get(service);
        List<TTbookOhlc> selected = bars.queryOhlc("BTCUSDT", "15m", "2025-08-12", "2025-08-12");
        Assert.assertEquals(1, selected.size());
        Assert.assertEquals("2025-08-12 00:00:00.000", selected.get(0).starttime);
        Assert.assertEquals(0, bars.queryOhlcCount("BTCUSDT", "15m", "2025-08-13", "2025-08-13"));

        Field scenesField = BacktestService.class.getDeclaredField("sceneTimelineService");
        scenesField.setAccessible(true);
        DeepSeekSceneTimelineService scenes =
                (DeepSeekSceneTimelineService) scenesField.get(service);
        DeepSeekSceneTimelineService.Timeline timeline = scenes.load(
                "BTCUSDT", "2025-08-12", "2025-08-12");
        Assert.assertEquals(2, timeline.size());
        Assert.assertTrue(timeline.cursor().at(Instant.parse("2025-08-12T12:01:00Z"))
                .matches("trend"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMismatchedHashBeforeInstall() throws Exception {
        Path dir = Files.createTempDirectory("local-research-hash-");
        Files.write(dir.resolve("BTCUSDT-15m.tsv"), BAR.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("BTCUSDT-scenes.tsv"), SCENE.getBytes(StandardCharsets.UTF_8));
        LocalResearchMarketData.install(new BacktestService(), dir, "BTCUSDT", "15m",
                "2025-08-12", "2025-08-12", "wrong", hash(SCENE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingSceneBucket() {
        List<DeepSeekSceneTimelineService.SceneRow> rows = LocalResearchMarketData.loadScenes(
                SCENE.split("\n")[0].getBytes(StandardCharsets.UTF_8));
        LocalResearchMarketData.requireSceneCoverage(rows, "2025-08-12", "2025-08-12");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedOhlc() {
        LocalResearchMarketData.loadBars(
                "2025-08-12 00:00:00.000000\t3\t2\t0.5\t1.5\t100\n"
                        .getBytes(StandardCharsets.UTF_8), "BTCUSDT");
    }

    private static String hash(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
