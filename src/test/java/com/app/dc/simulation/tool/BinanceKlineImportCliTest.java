package com.app.dc.simulation.tool;

import org.junit.Assert;
import org.junit.Test;

public class BinanceKlineImportCliTest {

    @Test
    public void storageIntervalShouldUseCanonicalUppercase() {
        Assert.assertEquals("15M", BinanceKlineImportCli.normalizeStorageInterval("15m"));
        Assert.assertEquals("1H", BinanceKlineImportCli.normalizeStorageInterval("1h"));
        Assert.assertEquals("1D", BinanceKlineImportCli.normalizeStorageInterval(" 1d "));
    }
}
