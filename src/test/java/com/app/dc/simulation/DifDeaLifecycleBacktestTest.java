package com.app.dc.simulation;

import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.service.simulation.BacktestModels;
import com.app.dc.service.simulation.BacktestReportService;
import com.app.dc.service.simulation.BacktestService;
import org.apache.log4j.BasicConfigurator;
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
import java.util.Collections;
import java.util.List;

/**
 * DIF/DEA生命周期策略的本地回测入口测试。
 */
public class DifDeaLifecycleBacktestTest {

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
     * 构造默认回测参数。
     */
    private BacktestParam defaultParam() {
        BacktestParam param = new BacktestParam();
        param.strategyName = "difDeaLifecycle";
        param.symbols = "ETHUSDT";
        param.text = "5m";
        param.beginDate = "2026-06-23";
        param.endDate = "2026-06-24";
        param.initialCapital = new BigDecimal("10000");
        param.feeRatePct = new BigDecimal("0.04");
        param.fallbackStopLossPct = new BigDecimal("6.0");
        param.fallbackTakeProfitPct = new BigDecimal("6.0");
        param.ignoreSentimentGuard = true;
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
