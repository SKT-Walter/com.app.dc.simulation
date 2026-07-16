package com.app.dc.service.dao;

import com.app.dc.service.simulation.BacktestModels;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    public void trialInsertBatchSizeDefaultsWhenConfigurationIsInvalid() throws Exception {
        BacktestResultClickHouseDao dao = new BacktestResultClickHouseDao();

        Assert.assertEquals(100, dao.normalizedTrialInsertBatchSize());

        Field field = BacktestResultClickHouseDao.class
                .getDeclaredField("optimizationTrialInsertBatchSize");
        field.setAccessible(true);
        field.setInt(dao, 250);

        Assert.assertEquals(250, dao.normalizedTrialInsertBatchSize());
    }

    @Test
    public void optimizationTrialsAreWrittenInBoundedBatches() throws Exception {
        BacktestResultClickHouseDao dao = new BacktestResultClickHouseDao();
        AtomicInteger addBatchCalls = new AtomicInteger();
        AtomicInteger executeBatchCalls = new AtomicInteger();
        AtomicInteger executeUpdateCalls = new AtomicInteger();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("addBatch".equals(method.getName())) {
                        addBatchCalls.incrementAndGet();
                    } else if ("executeBatch".equals(method.getName())) {
                        executeBatchCalls.incrementAndGet();
                        return new int[0];
                    } else if ("executeUpdate".equals(method.getName())) {
                        executeUpdateCalls.incrementAndGet();
                        return 1;
                    }
                    return null;
                });
        List<BacktestModels.OptimizationTrial> trials = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            BacktestModels.OptimizationTrial trial = new BacktestModels.OptimizationTrial();
            trial.trialNo = i + 1;
            trials.add(trial);
        }

        dao.insertOptimizationTrialBatches(statement, "bt-test", trials);

        Assert.assertEquals(205, addBatchCalls.get());
        Assert.assertEquals(3, executeBatchCalls.get());
        Assert.assertEquals(0, executeUpdateCalls.get());
    }
}
