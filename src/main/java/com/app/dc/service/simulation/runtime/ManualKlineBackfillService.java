package com.app.dc.service.simulation.runtime;

import com.app.dc.simulation.tool.BinanceKlineImportCli;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class ManualKlineBackfillService {

    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    @Autowired
    @Qualifier("strategyBacktestKlineAutofillExecutor")
    private ThreadPoolTaskExecutor strategyBacktestKlineAutofillExecutor;

    @Value("${strategy.backtest.kline-autofill.venue:BNFutures}")
    private String defaultVenue;

    @Value("${strategy.backtest.kline-autofill.mode:gap-fill}")
    private String defaultMode;

    @Value("${strategy.backtest.kline-autofill.limitPerCall:1500}")
    private int defaultLimitPerCall;

    @Value("${strategy.backtest.kline-autofill.sleepMs:250}")
    private long defaultSleepMs;

    @Value("${dbpool.cfg:./config/DBPoolConfig.ini}")
    private String defaultDbpoolCfg;

    @Value("${clickhouse.default:ClickHouse1}")
    private String defaultDbSourceName;

    private final Map<String, JobState> jobs = new ConcurrentHashMap<String, JobState>();
    private final ConcurrentLinkedDeque<String> recentJobIds = new ConcurrentLinkedDeque<String>();

    public Map<String, Object> trigger(Map<String, Object> request) {
        BackfillRequest req = BackfillRequest.from(request);
        req.applyDefaults(defaultVenue, defaultMode, defaultLimitPerCall, defaultSleepMs, defaultDbpoolCfg, defaultDbSourceName);
        req.validate();
        String jobId = buildJobId();
        JobState state = JobState.create(jobId, req);
        jobs.put(jobId, state);
        recentJobIds.addFirst(jobId);
        trimRecent();
        if (req.async) {
            try {
                strategyBacktestKlineAutofillExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        runJob(state, req);
                    }
                });
                state.message = "backfill accepted";
                return state.toResponse(true);
            } catch (RejectedExecutionException e) {
                state.status = "FAILED";
                state.message = "backfill rejected: " + e.getMessage();
                state.finishedAt = nowText();
                log.warn("ManualKlineBackfillService rejected, jobId:{}", jobId, e);
                return state.toResponse(false);
            }
        }
        runJob(state, req);
        return state.toResponse("SUCCESS".equalsIgnoreCase(state.status));
    }

    public Map<String, Object> query(String jobId, int limit) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (jobId != null && jobId.trim().length() > 0) {
            JobState state = jobs.get(jobId.trim());
            result.put("job", state == null ? null : state.toMap());
            result.put("found", state != null);
            return result;
        }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        int max = Math.max(1, limit);
        int count = 0;
        for (String id : recentJobIds) {
            JobState state = jobs.get(id);
            if (state != null) {
                list.add(state.toMap());
                count++;
                if (count >= max) {
                    break;
                }
            }
        }
        result.put("jobs", list);
        result.put("count", list.size());
        return result;
    }

    private void runJob(JobState state, BackfillRequest req) {
        state.status = "RUNNING";
        state.startedAt = nowText();
        state.message = "backfill running";
        log.info("ManualKlineBackfillService start, jobId:{}, symbols:{}, intervals:{}, yearsBack:{}, startDate:{}, endDate:{}, venue:{}, mode:{}",
                state.jobId, req.symbols, req.intervals, req.yearsBack, req.startDate, req.endDate, req.venue, req.mode);
        try {
            for (String interval : req.intervals) {
                Map<String, Object> step = new LinkedHashMap<String, Object>();
                step.put("interval", interval);
                step.put("symbols", req.symbols);
                step.put("startedAt", nowText());
                state.steps.add(step);
                List<String> args = new ArrayList<String>();
                args.add("--symbols");
                args.add(join(req.symbols));
                args.add("--interval");
                args.add(interval);
                if (req.startDate != null && req.endDate != null) {
                    args.add("--start-date");
                    args.add(req.startDate.toString());
                    args.add("--end-date");
                    args.add(req.endDate.toString());
                } else {
                    args.add("--years-back");
                    args.add(String.valueOf(req.yearsBack));
                }
                args.add("--venue");
                args.add(req.venue);
                args.add("--mode");
                args.add(req.mode);
                args.add("--limit-per-call");
                args.add(String.valueOf(req.limitPerCall));
                args.add("--sleep-ms");
                args.add(String.valueOf(req.sleepMs));
                args.add("--dbpool-cfg");
                args.add(req.dbpoolCfg);
                args.add("--db-source");
                args.add(req.dbSourceName);
                BinanceKlineImportCli.main(args.toArray(new String[args.size()]));
                step.put("status", "SUCCESS");
                step.put("finishedAt", nowText());
            }
            state.status = "SUCCESS";
            state.message = "backfill finished";
        } catch (Exception e) {
            state.status = "FAILED";
            state.message = e.getMessage();
            if (!state.steps.isEmpty()) {
                state.steps.get(state.steps.size() - 1).put("status", "FAILED");
                state.steps.get(state.steps.size() - 1).put("error", e.getMessage());
                state.steps.get(state.steps.size() - 1).put("finishedAt", nowText());
            }
            log.error("ManualKlineBackfillService failed, jobId:{}", state.jobId, e);
        } finally {
            state.finishedAt = nowText();
            log.info("ManualKlineBackfillService end, jobId:{}, status:{}, message:{}",
                    state.jobId, state.status, state.message);
        }
    }

    private void trimRecent() {
        while (recentJobIds.size() > 100) {
            recentJobIds.pollLast();
        }
    }

    private String buildJobId() {
        String time = new SimpleDateFormat("yyMMddHHmmss", Locale.ENGLISH).format(new Date());
        return "klinebf_" + time + "_" + NEXT_ID.getAndIncrement();
    }

    private String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(new Date());
    }

    private String join(List<String> items) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(items.get(i));
        }
        return builder.toString();
    }

    static class BackfillRequest {
        List<String> symbols = new ArrayList<String>();
        List<String> intervals = new ArrayList<String>();
        LocalDate startDate;
        LocalDate endDate;
        Integer yearsBack;
        String venue;
        String mode;
        Integer limitPerCall;
        Long sleepMs;
        String dbpoolCfg;
        String dbSourceName;
        boolean async = true;

        static BackfillRequest from(Map<String, Object> request) {
            BackfillRequest req = new BackfillRequest();
            req.symbols = parseList(request == null ? null : request.get("symbols"));
            req.intervals = parseList(request == null ? null : request.get("intervals"));
            req.startDate = parseDate(request == null ? null : request.get("startDate"));
            req.endDate = parseDate(request == null ? null : request.get("endDate"));
            req.yearsBack = parseInt(request == null ? null : request.get("yearsBack"));
            req.venue = text(request == null ? null : request.get("venue"));
            req.mode = text(request == null ? null : request.get("mode"));
            req.limitPerCall = parseInt(request == null ? null : request.get("limitPerCall"));
            req.sleepMs = parseLong(request == null ? null : request.get("sleepMs"));
            req.dbpoolCfg = text(request == null ? null : request.get("dbpoolCfg"));
            req.dbSourceName = text(request == null ? null : request.get("dbSource"));
            String asyncText = text(request == null ? null : request.get("async"));
            if (asyncText.length() > 0) {
                req.async = Boolean.parseBoolean(asyncText);
            }
            return req;
        }

        void applyDefaults(String venueDefault, String modeDefault, int limitDefault, long sleepDefault,
                           String dbpoolDefault, String dbSourceDefault) {
            if (symbols == null) {
                symbols = new ArrayList<String>();
            }
            if (intervals == null || intervals.isEmpty()) {
                intervals = new ArrayList<String>(Arrays.asList("15m", "1h", "1d"));
            }
            intervals = normalize(intervals);
            symbols = normalizeUpper(symbols);
            if (yearsBack == null || yearsBack.intValue() <= 0) {
                yearsBack = Integer.valueOf(2);
            }
            if (venue == null || venue.length() == 0) {
                venue = venueDefault;
            }
            if (mode == null || mode.length() == 0) {
                mode = modeDefault;
            }
            if (limitPerCall == null || limitPerCall.intValue() <= 0) {
                limitPerCall = Integer.valueOf(limitDefault);
            }
            if (sleepMs == null || sleepMs.longValue() < 0L) {
                sleepMs = Long.valueOf(sleepDefault);
            }
            if (dbpoolCfg == null || dbpoolCfg.length() == 0) {
                dbpoolCfg = dbpoolDefault;
            }
            if (dbSourceName == null || dbSourceName.length() == 0) {
                dbSourceName = dbSourceDefault;
            }
        }

        void validate() {
            if (symbols.isEmpty()) {
                throw new IllegalArgumentException("symbols is required");
            }
            if (intervals.isEmpty()) {
                throw new IllegalArgumentException("intervals is required");
            }
            if ((startDate == null) != (endDate == null)) {
                throw new IllegalArgumentException("startDate and endDate must both be provided");
            }
            if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("endDate must be >= startDate");
            }
        }

        private static List<String> parseList(Object value) {
            if (value == null) {
                return new ArrayList<String>();
            }
            if (value instanceof List) {
                List<?> raw = (List<?>) value;
                List<String> result = new ArrayList<String>();
                for (Object item : raw) {
                    String text = item == null ? "" : item.toString().trim();
                    if (text.length() > 0) {
                        result.add(text);
                    }
                }
                return result;
            }
            String text = value.toString().trim();
            if (text.length() == 0) {
                return new ArrayList<String>();
            }
            return new ArrayList<String>(Arrays.asList(text.split(",")));
        }

        private static List<String> normalize(List<String> source) {
            Set<String> dedup = new LinkedHashSet<String>();
            for (String item : source) {
                String text = item == null ? "" : item.trim().toLowerCase(Locale.ENGLISH);
                if (text.length() > 0) {
                    dedup.add(text);
                }
            }
            return new ArrayList<String>(dedup);
        }

        private static List<String> normalizeUpper(List<String> source) {
            Set<String> dedup = new LinkedHashSet<String>();
            for (String item : source) {
                String text = item == null ? "" : item.trim().toUpperCase(Locale.ENGLISH);
                if (text.length() > 0) {
                    dedup.add(text);
                }
            }
            return new ArrayList<String>(dedup);
        }

        private static LocalDate parseDate(Object value) {
            String text = text(value);
            return text.length() == 0 ? null : LocalDate.parse(text);
        }

        private static Integer parseInt(Object value) {
            String text = text(value);
            return text.length() == 0 ? null : Integer.valueOf(text);
        }

        private static Long parseLong(Object value) {
            String text = text(value);
            return text.length() == 0 ? null : Long.valueOf(text);
        }

        private static String text(Object value) {
            return value == null ? "" : value.toString().trim();
        }
    }

    static class JobState {
        String jobId;
        String status;
        String message;
        String submittedAt;
        String startedAt;
        String finishedAt;
        List<String> symbols = new ArrayList<String>();
        List<String> intervals = new ArrayList<String>();
        String venue;
        String mode;
        Integer yearsBack;
        String startDate;
        String endDate;
        boolean async;
        List<Map<String, Object>> steps = Collections.synchronizedList(new ArrayList<Map<String, Object>>());

        static JobState create(String jobId, BackfillRequest req) {
            JobState state = new JobState();
            state.jobId = jobId;
            state.status = "PENDING";
            state.message = "accepted";
            state.submittedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(new Date());
            state.symbols = new ArrayList<String>(req.symbols);
            state.intervals = new ArrayList<String>(req.intervals);
            state.venue = req.venue;
            state.mode = req.mode;
            state.yearsBack = req.yearsBack;
            state.startDate = req.startDate == null ? "" : req.startDate.toString();
            state.endDate = req.endDate == null ? "" : req.endDate.toString();
            state.async = req.async;
            return state;
        }

        Map<String, Object> toResponse(boolean accepted) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("accepted", accepted);
            result.put("jobId", jobId);
            result.put("status", status);
            result.put("message", message);
            result.put("async", async);
            result.put("data", toMap());
            return result;
        }

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("jobId", jobId);
            data.put("status", status);
            data.put("message", message);
            data.put("submittedAt", submittedAt);
            data.put("startedAt", startedAt);
            data.put("finishedAt", finishedAt);
            data.put("symbols", symbols);
            data.put("intervals", intervals);
            data.put("venue", venue);
            data.put("mode", mode);
            data.put("yearsBack", yearsBack);
            data.put("startDate", startDate);
            data.put("endDate", endDate);
            data.put("async", async);
            data.put("steps", new ArrayList<Map<String, Object>>(steps));
            return data;
        }
    }
}
