package com.app.dc.service.simulation.deterministic;

public final class StrategyRoutingState {
    final com.app.dc.strategy.core.routing.RoutingState core = new com.app.dc.strategy.core.routing.RoutingState();

    public String activeStrategy() { return core.activeStrategy; }
    public double activeScore() { return core.activeScore; }
}
