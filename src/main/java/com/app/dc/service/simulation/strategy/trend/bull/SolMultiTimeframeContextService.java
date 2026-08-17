package com.app.dc.service.simulation.strategy.trend.bull;

import org.springframework.stereotype.Service;

/**
 * SOL-owned 4H/1H context instance. The proven higher-timeframe wave parser is
 * reused, but its session memory remains isolated from ETH.
 */
@Service
public class SolMultiTimeframeContextService extends EthMultiTimeframeContextService {
}
