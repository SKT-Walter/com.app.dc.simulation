package com.app.dc.service.simulation.deterministic;

/** Session state for deterministic routing and its slow structural context. */
public final class DeterministicPipelineState {
    final StrategyRoutingState routerState;
    final StructuralTrendState structuralTrendState;
    final TrendCompressionState trendCompressionState;

    public DeterministicPipelineState(StrategyRoutingState routerState,
                                      StructuralTrendState structuralTrendState,
                                      TrendCompressionState trendCompressionState) {
        this.routerState = routerState;
        this.structuralTrendState = structuralTrendState;
        this.trendCompressionState = trendCompressionState;
    }
}
