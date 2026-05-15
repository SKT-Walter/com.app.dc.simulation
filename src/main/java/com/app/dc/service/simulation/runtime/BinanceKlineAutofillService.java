package com.app.dc.service.simulation.runtime;

import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.simulation.tool.BinanceKlineImportCli;
import com.app.dc.service.simulation.BacktestQueryService;
import com.gateway.connector.utils.JsonUtils;
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
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class BinanceKlineAutofillService {

    private static final String INSUFFICIENT_KLINE = "INSUFFICIENT_KLINE";
    private static final AtomicLong NEXT_ALLOWED_START_MS = new AtomicLong(0L);

    @Autowired(required = false)
    private ClickHouseDBUtils clickHouseDBUtils;

    @Autowired
    private BacktestQueryService backtestQueryService;

    @Autowired
    private StrategyBacktestTaskDao strategyBacktestTaskDao;

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

    @Value("${strategy.backtest.kline-autofill.minStartIntervalMs:3000}")
    private long minStartIntervalMs;

    @Value("${strategy.backtest.kline-autofill.cooldownMs:1800000}")
    private long cooldownMs;

    @Value("${dbpool.cfg:./config/DBPoolConfig.ini}")
    private String dbpoolCfg;

    @Value("${clickhouse.default:ClickHouse1}")
    private String dbSourceName;

    private final Set<String> inFlightBackfillKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> recentAttemptMsByKey = new ConcurrentHashMap<String, Long>();

    public AutofillTriggerResult triggerIfNeeded(StrategyBacktestTaskRow task, BacktestTaskSuspendedException error) {
        AutofillTriggerResult result = new AutofillTriggerResult();
        if (!enabled) {
            log.info("BinanceKlineAutofillService disabled, task:{}", task == null ? null : task.id);
            result.triggered = false;
            result.message = "autofill disabled";
            return result;
        }
        if (error == null || !INSUFFICIENT_KLINE.equalsIgnoreCase(error.getReason())) {
            result.triggered = false;
            result.message = "suspend reason not insufficient kline";
            return result;
        }
        AutofillRequest request = buildRequest(task, error.getDetail());
        if (request == null) {
            result.triggered = false;
            result.message = "autofill request not buildable";
            return result;
        }
        result.key = request.key();
        result.symbol = request.symbol;
        result.text = request.text;
        result.requiredBeginDate = request.requiredBeginDate == null ? "" : request.requiredBeginDate.toString();
        result.requiredEndDate = request.requiredEndDate == null ? "" : request.requiredEndDate.toString();
        result.requiredBars = request.requiredBars;
        result.actualBars = request.actualBars;
        result.missingBars = request.missingBars;
        if (request.skipAutofillBecauseNoMissingBars()) {
            log.info("BinanceKlineAutofillService skip autofill without missing bars, task:{}, key:{}, requiredBars:{}, actualBars:{}, missingBars:{}",
                    task == null ? null : task.id, request.key(), request.requiredBars, request.actualBars, request.missingBars);
            result.triggered = false;
            result.message = "autofill skipped: no missing bars";
            return result;
        }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, cooldownMs);
        if (cooldown > 0L) {
            Long lastAttemptAt = recentAttemptMsByKey.get(request.key());
            if (lastAttemptAt != null && now - lastAttemptAt.longValue() < cooldown) {
                long remainMs = cooldown - (now - lastAttemptAt.longValue());
                log.info("BinanceKlineAutofillService cooldown skip, task:{}, key:{}, remainMs:{}",
                        task == null ? null : task.id, request.key(), remainMs);
                result.triggered = false;
                result.duplicate = true;
                result.message = "autofill cooldown active: " + remainMs + "ms";
                return result;
            }
        }
        if (!inFlightBackfillKeys.add(request.key())) {
            log.info("BinanceKlineAutofillService skip duplicate running autofill, task:{}, key:{}",
                    task == null ? null : task.id, request.key());
            result.triggered = false;
            result.duplicate = true;
            result.message = "duplicate autofill already running";
            return result;
        }
        try {
            recentAttemptMsByKey.put(request.key(), Long.valueOf(now));
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
            result.triggered = true;
            result.message = "autofill scheduled";
        } catch (RejectedExecutionException e) {
            inFlightBackfillKeys.remove(request.key());
            log.warn("BinanceKlineAutofillService rejected, task:{}, key:{}",
                    task == null ? null : task.id, request.key(), e);
            result.triggered = false;
            result.message = "autofill rejected: " + e.getMessage();
        } catch (Exception e) {
            inFlightBackfillKeys.remove(request.key());
            log.warn("BinanceKlineAutofillService schedule error, task:{}, key:{}",
                    task == null ? null : task.id, request.key(), e);
            result.triggered = false;
            result.message = "autofill schedule error: " + e.getMessage();
        }
        return result;
    }

    private void runAutofill(StrategyBacktestTaskRow task, AutofillRequest request) {
        String threadName = Thread.currentThread().getName();
        long startNs = System.nanoTime();
        String nextRetryTime = null;
        try {
            long throttleWaitMs = reserveThrottleDelay();
            nextRetryTime = computeNextRetryTime();
            if (throttleWaitMs > 0) {
                updateRecoveryProgress(task, request, nextRetryTime, "WAITING_THROTTLE",
                        request.actualBars, request.missingBars,
                        "补数排队中，等待限频窗口释放", "");
                log.info("BinanceKlineAutofillService throttle wait, task:{}, key:{}, thread:{}, waitMs:{}, minStartIntervalMs:{}",
                        task == null ? null : task.id,
                        request.key(),
                        threadName,
                        throttleWaitMs,
                        minStartIntervalMs);
                Thread.sleep(throttleWaitMs);
            }
            updateRecoveryProgress(task, request, nextRetryTime, "IMPORTING",
                    request.actualBars, request.missingBars,
                    "补数任务已启动，正在向币安拉取缺失 K 线", "");
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
            int actualBars = queryBars(request.symbol, request.text, request.requiredBeginDate, request.requiredEndDate);
            int missingBars = Math.max(0, request.requiredBars - actualBars);
            updateRecoveryProgress(task, request, nextRetryTime, "VALIDATING",
                    actualBars, missingBars,
                    actualBars >= request.requiredBars
                            ? "补数已完成，正在校验是否满足重新回测条件"
                            : "补数已完成，当前仍缺少部分 K 线，等待下一轮补数",
                    "");
            if (actualBars >= request.requiredBars) {
                if (task != null && !StringUtils.isBlank(task.id)) {
                    updateRecoveryProgress(task, request, nextRetryTime, "READY_FOR_RETRY",
                            actualBars, 0,
                            "补数完成，任务已满足重试条件，系统即将重新执行回测", "");
                    strategyBacktestTaskDao.markRetryReadyNow(task.id, INSUFFICIENT_KLINE);
                }
                log.info("BinanceKlineAutofillService success, task:{}, key:{}, thread:{}, startDate:{}, endDate:{}, requiredBars:{}, actualBars:{}, missingBars:{}",
                        task == null ? null : task.id,
                        request.key(),
                        threadName,
                        request.startDate,
                        request.endDate,
                        request.requiredBars,
                        actualBars,
                        missingBars);
                log.info("BinanceKlineAutofillService retry ready now, task:{}, key:{}, thread:{}, reason:{}, nextRetryTime:now()",
                        task == null ? null : task.id,
                        request.key(),
                        threadName,
                        INSUFFICIENT_KLINE);
            } else {
                updateRecoveryProgress(task, request, nextRetryTime, "PARTIAL",
                        actualBars, missingBars,
                        "补数未完成，当前仍缺少 " + missingBars + " 根 K 线，系统会继续等待后续重试", "");
                log.warn("BinanceKlineAutofillService validation not enough, task:{}, key:{}, thread:{}, requiredBeginDate:{}, requiredEndDate:{}, requiredBars:{}, actualBars:{}, missingBars:{}",
                        task == null ? null : task.id,
                        request.key(),
                        threadName,
                        request.requiredBeginDate,
                        request.requiredEndDate,
                        request.requiredBars,
                        actualBars,
                        missingBars);
            }
        } catch (Exception e) {
            updateRecoveryProgress(task, request, nextRetryTime, "IMPORT_FAILED",
                    request.actualBars, request.missingBars,
                    "补数执行失败，等待系统下次自动重试", e.getMessage());
            log.warn("BinanceKlineAutofillService failed, task:{}, key:{}, thread:{}",
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

    private void updateRecoveryProgress(StrategyBacktestTaskRow task,
                                        AutofillRequest request,
                                        String nextRetryTime,
                                        String stage,
                                        int actualBars,
                                        int missingBars,
                                        String message,
                                        String error) {
        if (task == null || StringUtils.isBlank(task.id)) {
            return;
        }
        try {
            String payload = buildRecoveryPayload(task, request, nextRetryTime, stage, actualBars, missingBars, message, error);
            strategyBacktestTaskDao.refreshRecoveryProgress(task.id, payload, nextRetryTime);
        } catch (Exception e) {
            log.warn("BinanceKlineAutofillService updateRecoveryProgress failed, task:{}, key:{}, stage:{}",
                    task.id, request == null ? "" : request.key(), stage, e);
        }
    }

    private String buildRecoveryPayload(StrategyBacktestTaskRow task,
                                        AutofillRequest request,
                                        String nextRetryTime,
                                        String stage,
                                        int actualBars,
                                        int missingBars,
                                        String message,
                                        String error) {
        StrategyBacktestTaskPayloadEnvelope envelope = new StrategyBacktestTaskPayloadEnvelope();
        if (task != null && StringUtils.isNotBlank(task.payload)) {
            try {
                BacktestParam param = JsonUtils.Deserialize(task.payload, BacktestParam.class);
                if (param != null && (!StringUtils.isBlank(param.strategyName) || !StringUtils.isBlank(param.symbol) || !StringUtils.isBlank(param.text))) {
                    envelope.backtestParam = param;
                }
            } catch (Exception ignore) {
            }
            if (envelope.backtestParam == null) {
                try {
                    StrategyBacktestTaskPayloadEnvelope existing = JsonUtils.Deserialize(task.payload, StrategyBacktestTaskPayloadEnvelope.class);
                    if (existing != null) {
                        envelope.backtestParam = existing.backtestParam;
                        envelope.suspendDetail = existing.suspendDetail;
                        envelope.recoveryPlan = existing.recoveryPlan;
                    }
                } catch (Exception ignore) {
                }
            }
        }
        if (envelope.recoveryPlan == null) {
            envelope.recoveryPlan = new ConcurrentHashMap<String, Object>();
        }
        Map<String, Object> recoveryPlan = envelope.recoveryPlan;
        recoveryPlan.put("reason", INSUFFICIENT_KLINE);
        recoveryPlan.put("nextRetryTime", StringUtils.defaultString(nextRetryTime));
        recoveryPlan.put("autofillTriggered", true);
        recoveryPlan.put("autofillDuplicate", false);
        recoveryPlan.put("autofillKey", request == null ? "" : request.key());
        recoveryPlan.put("requiredBeginDate", request == null || request.requiredBeginDate == null ? "" : request.requiredBeginDate.toString());
        recoveryPlan.put("requiredEndDate", request == null || request.requiredEndDate == null ? "" : request.requiredEndDate.toString());
        recoveryPlan.put("requiredBars", request == null ? 0 : request.requiredBars);
        recoveryPlan.put("actualBars", Math.max(0, actualBars));
        recoveryPlan.put("missingBars", Math.max(0, missingBars));
        recoveryPlan.put("autofillMessage", StringUtils.defaultString(message));
        recoveryPlan.put("autofillError", StringUtils.defaultString(error));
        recoveryPlan.put("autofillStage", StringUtils.defaultString(stage));
        recoveryPlan.put("recoverable", true);
        return JsonUtils.Serializer(envelope);
    }

    private String computeNextRetryTime() {
        return java.time.LocalDateTime.now().plusMinutes(30)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private long reserveThrottleDelay() {
        long minGap = Math.max(0L, minStartIntervalMs);
        if (minGap <= 0L) {
            return 0L;
        }
        while (true) {
            long now = System.currentTimeMillis();
            long nextAllowed = NEXT_ALLOWED_START_MS.get();
            long scheduledStart = Math.max(now, nextAllowed);
            long newNextAllowed = scheduledStart + minGap;
            if (NEXT_ALLOWED_START_MS.compareAndSet(nextAllowed, newNextAllowed)) {
                return Math.max(0L, scheduledStart - now);
            }
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
        int requiredBars = intValue(detail.get("requiredBars"));
        int actualBars = intValue(detail.get("actualBars"));
        int missingBars = intValue(detail.get("missingBars"));
        LocalDate endDate = requiredEndDate == null ? LocalDate.now(ZoneOffset.UTC) : requiredEndDate;
        LocalDate startDate = requiredBeginDate == null
                ? endDate.minusYears(Math.max(1, yearsBack))
                : requiredBeginDate;
        if (endDate.isBefore(startDate)) {
            endDate = startDate;
        }
        AutofillRequest request = new AutofillRequest();
        request.symbol = symbol;
        request.text = text;
        request.earliestDate = earliestDate;
        request.requiredBeginDate = requiredBeginDate;
        request.requiredEndDate = requiredEndDate;
        request.requiredBars = requiredBars;
        request.actualBars = actualBars;
        request.missingBars = missingBars;
        request.startDate = startDate;
        request.endDate = endDate;
        request.venue = venue;
        return request;
    }

    private int queryBars(String symbol, String text, LocalDate beginDate, LocalDate endDate) {
        if (beginDate == null || endDate == null) {
            return 0;
        }
        try {
            return backtestQueryService.queryOhlcCount(symbol, text, beginDate.toString(), endDate.toString());
        } catch (Exception e) {
            log.warn("BinanceKlineAutofillService queryBars error, symbol:{}, text:{}, beginDate:{}, endDate:{}",
                    symbol, text, beginDate, endDate, e);
            return 0;
        }
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

    private int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static class EarliestKlineRow {
        public String earliestDate;
    }

    private static class AutofillRequest {
        private String symbol;
        private String text;
        private LocalDate earliestDate;
        private LocalDate requiredBeginDate;
        private LocalDate requiredEndDate;
        private int requiredBars;
        private int actualBars;
        private int missingBars;
        private LocalDate startDate;
        private LocalDate endDate;
        private String venue;

        private String key() {
            return venue + "|" + symbol + "|" + text;
        }

        private boolean skipAutofillBecauseNoMissingBars() {
            return requiredBars > 0 && actualBars >= requiredBars && missingBars <= 0;
        }
    }

    public static class AutofillTriggerResult {
        public boolean triggered;
        public boolean duplicate;
        public String key;
        public String symbol;
        public String text;
        public String requiredBeginDate;
        public String requiredEndDate;
        public int requiredBars;
        public int actualBars;
        public int missingBars;
        public String message;
    }
}
