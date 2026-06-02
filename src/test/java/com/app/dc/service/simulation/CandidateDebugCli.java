package com.app.dc.service.simulation;

import com.app.common.db.ClickHouseDBUtils;
import com.app.common.db.IDatabaseConnection;
import com.app.dc.service.simulation.runtime.ClickHouseStrategyBacktestTaskDao;
import com.app.dc.service.simulation.runtime.StrategyCandidateRow;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class CandidateDebugCli {

    public static void main(String[] args) throws Exception {
        String strategyName = required("trainer.backtest.strategyName");
        String strategyVersion = required("trainer.backtest.strategyVersion");
        Class<?> appClass = Class.forName("SIMSvr");
        ConfigurableApplicationContext context = new SpringApplicationBuilder(appClass)
                .web(WebApplicationType.NONE)
                .properties(
                        "strategy.backtest.task.enabled=false",
                        "spring.main.banner-mode=off"
                )
                .run(args);
        try {
            ensureClickHouseReady(context);
            ClickHouseStrategyBacktestTaskDao dao = context.getBean(ClickHouseStrategyBacktestTaskDao.class);
            Method readyMethod = ClickHouseStrategyBacktestTaskDao.class.getDeclaredMethod("ready");
            readyMethod.setAccessible(true);
            boolean ready = (Boolean) readyMethod.invoke(dao);
            Field candidateTableField = ClickHouseStrategyBacktestTaskDao.class.getDeclaredField("candidateTable");
            candidateTableField.setAccessible(true);
            String candidateTable = (String) candidateTableField.get(dao);
            System.out.println("=== DAO READY === " + ready);
            System.out.println("candidateTable=" + candidateTable);

            String sql = "select "
                    + "id as id,"
                    + "strategy_name as strategyName,"
                    + "strategy_version as strategyVersion,"
                    + "parent_version as parentVersion,"
                    + "category as category,"
                    + "scene as scene,"
                    + "generation_type as generationType,"
                    + "runtime_type as runtimeType,"
                    + "artifact_uri as artifactUri,"
                    + "entry_class as entryClass,"
                    + "description as description,"
                    + "parameters_json as parametersJson,"
                    + "payload as payload "
                    + "from " + candidateTable
                    + " where strategy_name='" + escape(strategyName) + "'"
                    + " and strategy_version='" + escape(strategyVersion) + "'"
                    + " order by create_time desc limit 1";
            System.out.println("sql=" + sql);

            try {
                List<StrategyCandidateRow> strictRows = ClickHouseDBUtils.queryListThrowsException(
                        sql, new Object[]{}, StrategyCandidateRow.class);
                System.out.println("queryListThrowsException.size=" + (strictRows == null ? "null" : strictRows.size()));
                if (strictRows != null && !strictRows.isEmpty()) {
                    StrategyCandidateRow row = strictRows.get(0);
                    System.out.println("strict.first.id=" + row.id);
                    System.out.println("strict.first.strategy=" + row.strategyName + "@" + row.strategyVersion);
                    System.out.println("strict.first.runtimeType=" + row.runtimeType);
                }
            } catch (Exception e) {
                System.out.println("queryListThrowsException.error=" + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.out);
            }

            List<StrategyCandidateRow> rows = ClickHouseDBUtils.queryList(sql, new Object[]{}, StrategyCandidateRow.class);
            System.out.println("queryList.size=" + (rows == null ? "null" : rows.size()));
            if (rows != null && !rows.isEmpty()) {
                StrategyCandidateRow row = rows.get(0);
                System.out.println("queryList.first.id=" + row.id);
                System.out.println("queryList.first.strategy=" + row.strategyName + "@" + row.strategyVersion);
                System.out.println("queryList.first.runtimeType=" + row.runtimeType);
            }

            StrategyCandidateRow daoRow = dao.loadCandidate(strategyName, strategyVersion);
            System.out.println("dao.loadCandidate=" + (daoRow == null ? "null" : daoRow.id + " " + daoRow.strategyName + "@" + daoRow.strategyVersion));
        } finally {
            context.close();
        }
    }

    private static void ensureClickHouseReady(ConfigurableApplicationContext context) throws Exception {
        String configPath = context.getEnvironment().getProperty("dbpool.cfg", "./config/DBPoolConfig.ini");
        String sourceName = context.getEnvironment().getProperty("clickhouse.default", "ClickHouse1");
        final Map<String, String> dbConfig = loadClickHouseConfig(configPath);
        final String url = dbConfig.get("CLICKHOUSE.DBUrl_0");
        final String user = dbConfig.get("CLICKHOUSE.DBUsername_0");
        final String password = dbConfig.get("CLICKHOUSE.DBPasswd_0");
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        ClickHouseDBUtils.setDatabaseConnection(new IDatabaseConnection() {
            @Override
            public Connection getConnection() {
                try {
                    return DriverManager.getConnection(url, user, password);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void freeConnection(Connection connection) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        });
        ClickHouseDBUtils.init(sourceName, configPath);
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing required system property: " + key);
        }
        return value.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
    }

    private static Map<String, String> loadClickHouseConfig(String path) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                continue;
            }
            int idx = line.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return map;
    }
}
