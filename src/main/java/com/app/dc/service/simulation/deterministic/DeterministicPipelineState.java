package com.app.dc.service.simulation.deterministic;

/** Session state for deterministic routing and its slow structural context. */
public final class DeterministicPipelineState {
    final StrategyRoutingState routerState;
    final StructuralTrendState structuralTrendState;

    public DeterministicPipelineState(StrategyRoutingState routerState,
                                      StructuralTrendState structuralTrendState) {
        this.routerState = routerState;
        this.structuralTrendState = structuralTrendState;
    }
}
