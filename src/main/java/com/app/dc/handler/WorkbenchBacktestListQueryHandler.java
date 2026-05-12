package com.app.dc.handler;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service("dc.sim.workbench.backtest.list.query")
public class WorkbenchBacktestListQueryHandler extends AbstractWorkbenchQueryHandler {
    @Override
    protected Object doHandle(Map<String, Object> request) {
        return workbenchService.queryBacktestList(request);
    }
}
