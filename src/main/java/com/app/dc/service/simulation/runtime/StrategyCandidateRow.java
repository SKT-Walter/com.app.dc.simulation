package com.app.dc.service.simulation.runtime;

import com.app.common.utils.JsonUtils;
import com.app.dc.pipeline.StrategyPipelineService;
import com.app.dc.signal.StrategyDefinition;
import com.app.dc.signal.StrategyParametersSupport;
import com.app.dc.signal.StrategyRuntimeType;

import java.util.LinkedHashMap;
import java.util.Map;

public class StrategyCandidateRow {
    public String id;
    public String strategyName;
    public String strategyVersion;
    public String parentVersion;
    public String category;
    public String scene;
    public String generationType;
    public String runtimeType;
    public String artifactUri;
    public String entryClass;
    public String description;
    public String parametersJson;
    public String payload;

    public StrategyDefinition toDefinition() {
        return toDefinition(null);
    }

    public StrategyDefinition toDefinition(Map<String, Object> overrideParams) {
        StrategyDefinition definition = new StrategyDefinition();
        definition.strategyName = strategyName;
        definition.strategyVersion = strategyVersion;
        definition.category = category;
        definition.scene = scene;
        definition.runtimeType = StrategyRuntimeType.from(runtimeType);
        definition.artifactUri = artifactUri;
        definition.entryClass = entryClass;
        definition.parameters.putAll(StrategyParametersSupport.resolveRuntimeParams(parametersJson, overrideParams));
        return definition;
    }

    public Map<String, Object> payloadMap() {
        return parsePayload(payload);
    }

    public String pipelineRunId() {
        Map<String, Object> data = parsePayload(payload);
        Object value = data.get(StrategyPipelineService.PIPELINE_RUN_ID);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object obj = JsonUtils.Deserialize(json, Map.class);
            if (obj instanceof Map) {
                return (Map<String, Object>) obj;
            }
        } catch (Exception ignore) {
        }
        return new LinkedHashMap<String, Object>();
    }
}
