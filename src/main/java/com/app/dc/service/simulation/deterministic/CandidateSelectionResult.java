package com.app.dc.service.simulation.deterministic;

import com.app.dc.service.simulation.dynamic.DynamicStrategyMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CandidateSelectionResult {
    public final List<DynamicStrategyMeta> candidates = new ArrayList<DynamicStrategyMeta>();
    public final Map<String, String> rejectedStrategies = new LinkedHashMap<String, String>();
}
