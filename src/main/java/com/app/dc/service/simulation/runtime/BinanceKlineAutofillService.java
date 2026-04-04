package com.app.dc.service.simulation.runtime;

import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.simulation.tool.BinanceKlineImportCli;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Service
@Slf4j
public class BinanceKlineAutofillService {

    private static final String INSUFFICIENT_KLINE = "INSUFFICIENT_KLINE";

    @Autowired(required = false)
    private ClickHouseDBUtils clickHouseDBUtils;

    @Autowired
    @Qualifier("strategyBacktestKlineAutofillExecutor")
    private ThreadPoolTaskExecutor strategyBacktestKlineAutofillExecutor;

    @Value("${strategy.backtest.kline-autofill.enabled:true}")
    private boolean enabled;

    @Value("${strategy.backtest.kline-autofill.yearsBack:2}")
    private int yearsBack;

    @Value("${strategy.backtest.kline-autofill.venue:BNFutures}")
    private String venue;

    @Value("${strategy.backtest.kline-autofill.mode:gap-fill}")
    private String mode;

    @Value("${strategy.backtest.kline-autofill.limitPerCall:1500}")
    private int limitPerCall;

    @Value("${strategy.backtest.kline-autofill.sleepMs:250}")
    private long sleepMs;

    @Value("${dbpool.cfg:./config/DBPoolConfig.ini}")
    private String dbpoolCfg;

    @Value("${clickhouse.default:ClickHouse1}")
    private String dbSourceName;

    private final Set<String> inFlightBackfillKeys = ConcurrentHashMap.newKeySet();

    public void triggerIfNeeded(StrategyBacktestTaskRow task, BacktestTaskSuspendedException error) {
        if (!enabled) {
            log.info("BinanceKlineAutofillService disabled, task:{}", task == null ? null : task.id);
            return;
        }
        if (error == null || !INSUFFICIENT_KLINE.equalsIgnoreCase(error.getReason())) {
            return;
        }
        AutofillRequest request = buildRequest(task, error.getDetail());
        if (request == null) {
            return;
        }
        if (!inFlightBackfillKeys.add(request.key())) {
            log.info("BinanceKlineAutofillService skip duplicate running autofill, task:{}, key:{}",
                    task == null ? null : task.id, request.key());
            return;
        }
        try {
            strategyBacktestKlineAutofillExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runAutofill(task, request);
                }
            });
            log.info("BinanceKlineAutofillService scheduled, task:{}, key:{}, startDate:{}, endDate:{}, threadPoolActive:{}",
                    task == null ? null : task.id,
                    request.key(),
                    request.startDate,
                    request.endDate,
                    strategyBacktestKlineAutofillExecutor.getActiveCount());
        } catch (RejectedExecutionException e) {
            inFlightBackfillKeys.remove(request.key());
            log.warn("BinanceKlineAutofillService rejected, task:{}, key:{}",
                    task == null ? null : task.id, request.key(), e);
        } catch (Exception e) {
            inFlightBackfillKeys.remove(request.key());
            log.error("BinanceKlineAutofillService schedule error, task:{}, key:{}",
                    task == null ? null : task.id, request.key(), e);
        }
    }

    private void runAutofill(StrategyBacktestTaskRow task, AutofillRequest request) {
        String threadName = Thread.currentThread().getName();
        long startNs = System.nanoTime();
        try {
            log.info("BinanceKlineAutofillService start, task:{}, key:{}, thread:{}, earliestDate:{}, requiredBeginDate:{}, requiredEndDate:{}",
                    task == null ? null : task.id,
                    request.key(),
                    threadName,
                    request.earliestDate,
                    request.requiredBeginDate,
                    request.requiredEndDate);
            BinanceKlineImportCli.main(new String[]{
                    "--symbols", request.symbol,
                    "--interval", request.text,
                    "--start-date", request.startDate.toString(),
                    "--end-date", request.endDate.toString(),
                    "--venue", venue,
                    "--mode", mode,
                    "--limit-per-call", String.valueOf(limitPerCall),
                    "--sleep-ms", String.valueOf(sleepMs),
                    "--dbpool-cfg", dbpoolCfg,
                    "--db-source", dbSourceName
            });
            log.info("BinanceKlineAutofillService success, task:{}, key:{}, thread:{}, startDate:{}, endDate:{}",
                    task == null ? null : task.id,
                    request.key(),
                    threadName,
                    request.startDate,
                    request.endDate);
        } catch (Exception e) {
            log.error("BinanceKlineAutofillService failed, task:{}, key:{}, thread:{}",
                    task == null ? null : task.id,
                    request.key(),
                    threadName,
                    e);
        } finally {
            inFlightBackfillKeys.remove(request.key());
            long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            log.info("BinanceKlineAutofillService end, task:{}, key:{}, thread:{}, elapsedMs:{}, inFlight:{}",
                    task == null ? null : task.id,
                    request.key(),
                    threadName,
                    elapsedMs,
                    inFlightBackfillKeys.size());
        }
    }

    private AutofillRequest buildRequest(StrategyBacktestTaskRow task, Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            log.warn("BinanceKlineAutofillService missing suspend detail, task:{}", task == null ? null : task.id);
            return null;
        }
        String symbol = stringValue(detail.get("symbol"));
        String text = stringValue(detail.get("text"));
        if (StringUtils.isBlank(symbol) || StringUtils.isBlank(text)) {
            log.warn("BinanceKlineAutofillService missing symbol/text, task:{}, detail:{}",
                    task == null ? null : task.id, detail);
            return null;
        }
        LocalDate earliestDate = queryEarliestDate(symbol, text);
        LocalDate requiredBeginDate = parseDate(detail.get("requiredBeginDate"));
        LocalDate requiredEndDate = parseDate(detail.get("requiredEndDate"));
        LocalDate startDate;
        LocalDate endDate;
        if (earliestDate != null) {
            startDate = earliestDate.minusYears(Math.max(1, yearsBack));
            endDate = earliestDate.minusDays(1L);
        } else {
            endDate = requiredEndDate == null ? LocalDate.now(ZoneOffset.UTC) : requiredEndDate;
            startDate = endDate.minusYears(Math.max(1, yearsBack));
        }
        if (requiredEndDate != null && endDate.isAfter(requiredEndDate)) {
            endDate = requiredEndDate;
        }
        if (endDate.isBefore(startDate)) {
            endDate = startDate;
        }
        AutofillRequest request = new AutofillRequest();
        request.symbol = symbol;
        request.text = text;
        request.earliestDate = earliestDate;
        request.requiredBeginDate = requiredBeginDate;
        request.requiredEndDate = requiredEndDate;
        request.startDate = startDate;
        request.endDate = endDate;
        request.venue = venue;
        return request;
    }

    private LocalDate queryEarliestDate(String symbol, String text) {
        if (clickHouseDBUtils == null || StringUtils.isBlank(clickHouseDBUtils.getDbSourceName())) {
            return null;
        }
        String sql = "select toString(min(startTime)) as earliestDate "
                + "from dc.kline where venue=? and securityID=? and lowerUTF8(text)=lowerUTF8(?)";
        try {
            List<EarliestKlineRow> rows = ClickHouseDBUtils.queryList(sql,
                    new Object[]{venue, symbol, text},
                    EarliestKlineRow.class);
            if (rows == null || rows.isEmpty() || StringUtils.isBlank(rows.get(0).earliestDate)) {
                return null;
            }
            return LocalDate.parse(rows.get(0).earliestDate.substring(0, 10));
        } catch (Exception e) {
            log.warn("BinanceKlineAutofillService queryEarliestDate error, symbol:{}, text:{}",
                    symbol, text, e);
            return null;
        }
    }

    private LocalDate parseDate(Object value) {
        String text = stringValue(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static class EarliestKlineRow {
        public String earliestDate;
    }

    private static class AutofillRequest {
        private String symbol;
        private String text;
        private LocalDate earliestDate;
        private LocalDate requiredBeginDate;
        private LocalDate requiredEndDate;
        private LocalDate startDate;
        private LocalDate endDate;
        private String venue;

        private String key() {
            return venue + "|" + symbol + "|" + text;
        }
    }
}
