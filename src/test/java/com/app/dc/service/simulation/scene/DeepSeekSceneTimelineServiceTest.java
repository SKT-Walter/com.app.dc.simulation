package com.app.dc.service.simulation.scene;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;

public class DeepSeekSceneTimelineServiceTest {

    @Test
    public void keepsLatestSuccessfulScenePerBucketAndNeverLooksAhead() {
        DeepSeekSceneTimelineService.SceneRow first = row("2026-07-01 00:01:00", "range", "SUCCESS");
        DeepSeekSceneTimelineService.SceneRow laterSuccess = row("2026-07-01 00:03:00", "trend", "SUCCESS");
        DeepSeekSceneTimelineService.SceneRow laterFailure = row("2026-07-01 00:05:00", "no_trade", "FAILED");

        DeepSeekSceneTimelineService.Timeline timeline = DeepSeekSceneTimelineService.buildTimeline(
                Arrays.asList(first, laterSuccess, laterFailure), 13);
        DeepSeekSceneTimelineService.Cursor cursor = timeline.cursor();

        Assert.assertNull(cursor.at(timeline.firstTime().minusSeconds(1)));
        DeepSeekSceneTimelineService.ScenePoint point = cursor.at(timeline.firstTime().plusSeconds(60));
        Assert.assertNotNull(point);
        Assert.assertEquals("trend", point.scene);
        Assert.assertTrue(point.matches("trend"));
        Assert.assertFalse(point.matches("range"));
    }

    @Test
    public void failedOnlyBucketBecomesNoTradeAndExpires() {
        DeepSeekSceneTimelineService.Timeline timeline = DeepSeekSceneTimelineService.buildTimeline(
                Arrays.asList(row("2026-07-01 12:00:00", "trend", "FAILED")), 13);
        DeepSeekSceneTimelineService.Cursor cursor = timeline.cursor();

        DeepSeekSceneTimelineService.ScenePoint point = cursor.at(timeline.firstTime().plusSeconds(1800));
        Assert.assertNotNull(point);
        Assert.assertEquals("no_trade", point.scene);
        Assert.assertFalse(point.matches("trend"));
        Assert.assertNull(cursor.at(timeline.firstTime().plus(Duration.ofHours(14))));
    }

    private DeepSeekSceneTimelineService.SceneRow row(String time, String scene, String status) {
        DeepSeekSceneTimelineService.SceneRow row = new DeepSeekSceneTimelineService.SceneRow();
        row.runTime = time;
        row.scene = scene;
        row.status = status;
        row.promptVersion = "deepseek_market_scene_v3";
        return row;
    }

}
