package com.app.dc.service.simulation.runtime;

import org.junit.Assert;
import org.junit.Test;

public class WalkForwardWindowPolicyTest {

    @Test
    public void sliceStepCoversValidateAndForwardWithoutOverlap() {
        Assert.assertEquals(44, WalkForwardWindowPolicy.sliceStepDays(30, 14));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sliceStepRejectsInvalidWindows() {
        WalkForwardWindowPolicy.sliceStepDays(30, 0);
    }
}
