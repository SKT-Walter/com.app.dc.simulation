package com.app.dc.handler;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service("dc.sim.workbench.backtest.report.query")
public class WorkbenchBacktestReportQueryHandler extends AbstractWorkbenchQueryHandler {
    @Override
    protected Object doHandle(Map<String, Object> request) {
        return workbenchService.queryBacktestReport(request);
    }
}
