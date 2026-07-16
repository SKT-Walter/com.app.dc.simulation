package com.app.dc.simulation;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.service.simulation.BacktestService;
import com.app.dc.service.simulation.strategy.lifecycle.DifDeaLifecycleBacktestStrategy;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.PropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * DIF/DEA生命周期策略的本地回测入口测试。
 */
public class DifDeaLifecycleBacktestTest {

    private static final LocalDate TWENTY_DAY_BACKTEST_BEGIN_DATE = LocalDate.of(2025, 4, 1);
    private static final int TWENTY_DAY_BACKTEST_PERIOD_DAYS = 20;
    private static final LocalDate CONTINUOUS_ANNUAL_BEGIN_DATE = LocalDate.of(2025, 4, 1);
    private static final LocalDate CONTINUOUS_ANNUAL_END_DATE = LocalDate.of(2026, 4, 1);
    private static final int CONTINUOUS_QUERY_CHUNK_DAYS = 2;

    /**
     * 直接启动精简Spring上下文并运行生命周期策略回测。
     */
    @Test
    public void runDifDeaLifecycleBacktest() throws Exception {
        BasicConfigurator.configure();
        PropertyConfigurator.configure("./config/log4j.ini");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .properties("spring.config.location=file:./config/application.properties")
                .properties("binanceBacktestStageGuardEnabled=false")
                .properties("binanceBacktestSentimentGuardEnabled=false")
                .run();
        try {
            BacktestService backtestService = context.getBean(BacktestService.class);
            BacktestReportService reportService = context.getBean(BacktestReportService.class);

            BacktestModels.BacktestResponse response = backtestService.run(defaultParam());
            String reportPath = reportService.writeReport(response);
            String compareReportPath = reportService.writeCompareReport(response);

            Assert.assertNotNull(response);
            Assert.assertNotNull(response.results);
            printSummary(response, reportPath, compareReportPath);
        } finally {
            context.close();
        }
    }

    /**
     * 按默认参数的日期范围逐天运行回测，并为每天单独输出报告。
     */
    @Test
    public void runDifDeaLifecycleDailyBacktest() throws Exception {
        BasicConfigurator.configure();
        PropertyConfigurator.configure("./config/log4j.ini");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .properties("spring.config.location=file:./config/application.properties")
                .properties("binanceBacktestStageGuardEnabled=false")
                .properties("binanceBacktestSentimentGuardEnabled=false")
                .run();
        try {
            BacktestService backtestService = context.getBean(BacktestService.class);
            BacktestReportService reportService = context.getBean(BacktestReportService.class);
            BacktestParam rangeParam = defaultParam();
            LocalDate beginDate = LocalDate.parse(rangeParam.beginDate);
            LocalDate endDate = LocalDate.parse(rangeParam.endDate);

            Assert.assertFalse("endDate must not be before beginDate", endDate.isBefore(beginDate));
            for (LocalDate current = beginDate; !current.isAfter(endDate); current = current.plusDays(1)) {
                BacktestParam dailyParam = copyDailyParam(rangeParam, current);
                BacktestModels.BacktestResponse response = backtestService.run(dailyParam);
                String reportPath = reportService.writeReport(response);
                String compareReportPath = reportService.writeCompareReport(response);

                Assert.assertNotNull(response);
                Assert.assertNotNull(response.results);
                printDailySummary(current, response, reportPath, compareReportPath);
            }
        } finally {
            context.close();
        }
    }

    /**
     * 从固定起始日按20天一个区间顺序回测到当天，并为每段单独输出报告。
     */
    @Test
    public void runDifDeaLifecycleTwentyDayBacktests() throws Exception {
        BasicConfigurator.configure();
        PropertyConfigurator.configure("./config/log4j.ini");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .properties("spring.config.location=file:./config/application.properties")
                .properties("binanceBacktestStageGuardEnabled=false")
                .properties("binanceBacktestSentimentGuardEnabled=false")
                .run();
        try {
            BacktestService backtestService = context.getBean(BacktestService.class);
            BacktestReportService reportService = context.getBean(BacktestReportService.class);
            BacktestParam template = defaultParam();
            LocalDate today = LocalDate.of(2026, 4, 1);//LocalDate.now();

            Assert.assertFalse("today must not be before batch begin date",
                    today.isBefore(TWENTY_DAY_BACKTEST_BEGIN_DATE));
            int segmentNumber = 1;
            for (LocalDate segmentBegin = TWENTY_DAY_BACKTEST_BEGIN_DATE;
                 !segmentBegin.isAfter(today);
                 segmentBegin = segmentBegin.plusDays(TWENTY_DAY_BACKTEST_PERIOD_DAYS)) {
                LocalDate calculatedEnd = segmentBegin.plusDays(TWENTY_DAY_BACKTEST_PERIOD_DAYS - 1L);
                LocalDate segmentEnd = calculatedEnd.isAfter(today) ? today : calculatedEnd;
                BacktestParam segmentParam = copyRangeParam(template, segmentBegin, segmentEnd);
                BacktestModels.BacktestResponse response = backtestService.run(segmentParam);
                String fileTag = segmentBegin.format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "_" + segmentEnd.format(DateTimeFormatter.BASIC_ISO_DATE);
                String reportPath = reportService.writeReport(response, fileTag);

                Assert.assertNotNull(response);
                Assert.assertNotNull(response.results);
                printTwentyDaySummary(segmentNumber, segmentBegin, segmentEnd, response, reportPath);
                segmentNumber++;
            }
        } finally {
            context.close();
        }
    }

    /**
     * 按2天查询块连续回放全年数据，并只输出一份全年报告。
     */
    @Test
    public void runDifDeaLifecycleContinuousAnnualBacktest() throws Exception {
        BasicConfigurator.configure();
        PropertyConfigurator.configure("./config/log4j.ini");

        Logger lifecycleLogger = Logger.getLogger(DifDeaLifecycleBacktestStrategy.class);
        Level originalLevel = lifecycleLogger.getLevel();
        lifecycleLogger.setLevel(Level.WARN);
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(TestApp.class)
                    .properties("spring.config.location=file:./config/application.properties")
                    .properties("binanceBacktestStageGuardEnabled=false")
                    .properties("binanceBacktestSentimentGuardEnabled=false")
                    .run();
            BacktestService backtestService = context.getBean(BacktestService.class);
            BacktestReportService reportService = context.getBean(BacktestReportService.class);
            BacktestParam annualParam = copyRangeParam(defaultParam(),
                    CONTINUOUS_ANNUAL_BEGIN_DATE, CONTINUOUS_ANNUAL_END_DATE);

            BacktestModels.BacktestResponse response = backtestService
                    .runContinuousChunked(annualParam, CONTINUOUS_QUERY_CHUNK_DAYS);
            String reportPath = reportService.writeReport(response, "continuous_20250401_20260401");

            Assert.assertNotNull(response);
            Assert.assertNotNull(response.results);
            System.out.println("continuous_annual_beginDate=" + CONTINUOUS_ANNUAL_BEGIN_DATE
                    + ", endDate=" + CONTINUOUS_ANNUAL_END_DATE
                    + ", chunkDays=" + CONTINUOUS_QUERY_CHUNK_DAYS);
            printSummary(response, reportPath, "");
        } finally {
            if (context != null) {
                context.close();
            }
            lifecycleLogger.setLevel(originalLevel);
        }
    }

    /**
     * 构造默认回测参数。
     */
    private BacktestParam defaultParam() {
        BacktestParam param = new BacktestParam();
        param.strategyName = "difDeaLifecycle";
        param.symbols = "ETHUSDT";
        param.text = "5m";
//        2026-01-21～2026-02-27
        param.beginDate = "2026-01-21";
        param.endDate = "2026-02-27";

//        param.beginDate = "2025-04-20";
//        param.endDate = "2025-05-10";

//        param.beginDate = "2026-04-01";
//        param.endDate = "2026-04-20";

//        param.beginDate = "2026-05-11";
//        param.endDate = "2026-05-31";


        //difDeaLifecycle_ETHUSDT_5M_20260702_160539.md
//        param.beginDate = "2026-05-01";
//        param.endDate = "2026-05-15";

//        param.beginDate = "2026-05-15";
//        param.endDate = "2026-05-30";

        //difDeaLifecycle_ETHUSDT_5M_20260702_161025.md
//        param.beginDate = "2026-06-10";
//        param.endDate = "2026-06-30";

//        param.beginDate = "2026-06-25";
//        param.endDate = "2026-07-13";

        param.initialCapital = new BigDecimal("10000");
        param.feeRatePct = new BigDecimal("0.04");
        param.fallbackStopLossPct = new BigDecimal("6.0");
        param.fallbackTakeProfitPct = new BigDecimal("6.0");
        param.ignoreSentimentGuard = true;
        return param;
    }

    /**
     * 基于区间参数生成某一天的回测参数。
     */
    private BacktestParam copyDailyParam(BacktestParam source, LocalDate date) {
        BacktestParam param = new BacktestParam();
        param.strategyName = source.strategyName;
        param.symbol = source.symbol;
        param.symbols = source.symbols;
        param.text = source.text;
        param.beginDate = date.toString();
        param.endDate = date.toString();
        param.initialCapital = source.initialCapital;
        param.feeRatePct = source.feeRatePct;
        param.fallbackStopLossPct = source.fallbackStopLossPct;
        param.fallbackTakeProfitPct = source.fallbackTakeProfitPct;
        param.maxHoldBars = source.maxHoldBars;
        param.ignoreSentimentGuard = source.ignoreSentimentGuard;
        return param;
    }

    /**
     * 基于默认参数生成指定日期区间的独立回测参数。
     */
    private BacktestParam copyRangeParam(BacktestParam source, LocalDate beginDate, LocalDate endDate) {
        BacktestParam param = new BacktestParam();
        param.strategyName = source.strategyName;
        param.symbol = source.symbol;
        param.symbols = source.symbols;
        param.text = source.text;
        param.beginDate = beginDate.toString();
        param.endDate = endDate.toString();
        param.initialCapital = source.initialCapital;
        param.feeRatePct = source.feeRatePct;
        param.fallbackStopLossPct = source.fallbackStopLossPct;
        param.fallbackTakeProfitPct = source.fallbackTakeProfitPct;
        param.maxHoldBars = source.maxHoldBars;
        param.ignoreSentimentGuard = source.ignoreSentimentGuard;
        return param;
    }

    /**
     * 打印回测核心指标和报告路径。
     */
    private void printSummary(BacktestModels.BacktestResponse response, String reportPath, String compareReportPath) {
        List<BacktestModels.BacktestResult> results = response.results == null
                ? Collections.<BacktestModels.BacktestResult>emptyList()
                : response.results;
        System.out.println("strategy=" + response.strategyName
                + ", symbol=" + response.symbol
                + ", symbols=" + response.symbols
                + ", text=" + response.text
                + ", beginDate=" + response.beginDate
                + ", endDate=" + response.endDate);
        System.out.println("report_path=" + reportPath);
        System.out.println("compare_report_path=" + compareReportPath);

        if (results.isEmpty()) {
            System.out.println("No backtest result. Please check whether 5M data exists in dc.kline_view.");
            return;
        }
        for (BacktestModels.BacktestResult result : results) {
            System.out.println("result strategy=" + result.strategyName
                    + ", symbol=" + result.symbol
                    + ", bars=" + result.totalBars
                    + ", trades=" + result.tradeCount
                    + ", winRate=" + result.winRate
                    + ", totalReturnPct=" + result.totalReturnPct
                    + ", maxDrawdownPct=" + result.maxDrawdownPct
                    + ", finalCapital=" + result.finalCapital);
        }
    }

    /**
     * 打印单日回测的核心指标和报告路径。
     */
    private void printDailySummary(LocalDate date,
                                   BacktestModels.BacktestResponse response,
                                   String reportPath,
                                   String compareReportPath) {
        System.out.println("daily_backtest_date=" + date);
        printSummary(response, reportPath, compareReportPath);
    }

    /**
     * 打印20天分段回测的核心指标和独立报告路径。
     */
    private void printTwentyDaySummary(int segmentNumber,
                                       LocalDate beginDate,
                                       LocalDate endDate,
                                       BacktestModels.BacktestResponse response,
                                       String reportPath) {
        System.out.println("twenty_day_segment=" + segmentNumber
                + ", beginDate=" + beginDate
                + ", endDate=" + endDate
                + ", report_path=" + reportPath);
        List<BacktestModels.BacktestResult> results = response.results == null
                ? Collections.<BacktestModels.BacktestResult>emptyList()
                : response.results;
        if (results.isEmpty()) {
            System.out.println("No backtest result for segment " + segmentNumber + ".");
            return;
        }
        for (BacktestModels.BacktestResult result : results) {
            System.out.println("segment_result strategy=" + result.strategyName
                    + ", symbol=" + result.symbol
                    + ", bars=" + result.totalBars
                    + ", trades=" + result.tradeCount
                    + ", winRate=" + result.winRate
                    + ", totalReturnPct=" + result.totalReturnPct
                    + ", maxDrawdownPct=" + result.maxDrawdownPct
                    + ", finalCapital=" + result.finalCapital);
        }
    }

    @SpringBootApplication
    @ComponentScan(
            basePackages = {"com.app.dc.service.simulation", "com.app.dc.service.dao", "com.app.common.db"},
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "com\\.app\\.dc\\.service\\.simulation\\.runtime\\..*"
            )
    )
    @PropertySource("file:./config/application.properties")
    /**
     * 回测测试专用的精简Spring Boot配置。
     */
    public static class TestApp {
    }
}
