package com.app.dc.service.dao;

import com.app.dc.service.simulation.BacktestModels;
import org.junit.Assert;
import org.junit.Test;

public class BacktestResultClickHouseDaoTest {

    @Test
    public void requiredPersistenceFailsClosedWhenStorageIsUnavailable() {
        BacktestResultClickHouseDao dao = new BacktestResultClickHouseDao();

        try {
            dao.insertResultsRequired("bt-test", "report.html", new BacktestModels.BacktestResponse());
            Assert.fail("required persistence must reject unavailable storage");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("disabled"));
        }
    }

    @Test
    public void optionalPersistenceKeepsLegacyBestEffortBehavior() {
        BacktestResultClickHouseDao dao = new BacktestResultClickHouseDao();
        dao.insertResults("bt-test", "report.html", new BacktestModels.BacktestResponse());
    }
}
