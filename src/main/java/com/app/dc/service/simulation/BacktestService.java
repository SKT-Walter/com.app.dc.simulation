package com.app.dc.service.simulation;

import com.app.dc.po.Signal;
import com.app.dc.po.Side;
import com.app.dc.po.TTbookOhlc;
import com.app.dc.po.backtest.BacktestParam;
import com.app.dc.strategy.core.StrategyRuntimeModels;
import com.app.dc.strategy.core.StrategyRegistry;
import com.app.dc.strategy.core.StrategyRuntimeModels.StrategyRunResponse;
import com.app.dc.strategy.core.StrategyRuntimeModels.StrategyRunResult;
import com.app.dc.strategy.core.StrategyRuntimeModels.EquityContext;
import com.app.dc.strategy.core.StrategyRuntimeModels.Position;
import com.app.dc.strategy.core.StrategyRuntimeModels.TradeRecord;
import com.app.dc.service.simulation.strategy.BinanceBacktestMarketGuard;
import com.app.dc.strategy.core.deterministic.DeterministicStrategyPipeline;
import com.app.dc.strategy.core.deterministic.DeterministicPipelineState;
import com.app.dc.strategy.core.deterministic.DeterministicPipelineResult;
import com.app.dc.strategy.core.deterministic.StrategyRoutingDecision;
import com.app.dc.strategy.core.deterministic.StructuralTrendService;
import com.app.dc.strategy.core.deterministic.StructuralTrendSnapshot;
import com.app.dc.strategy.core.deterministic.StructuralTrendState;
import com.app.dc.strategy.core.dynamic.MarketRegime;
import com.app.dc.strategy.core.dynamic.MarketRegimeService;
import com.app.dc.strategy.core.strategy.exit.PositionExitDecision;
import com.app.dc.strategy.core.strategy.exit.StrategyPositionExitContext;
import com.app.dc.strategy.core.strategy.exit.StrategyPositionExitService;
import com.app.dc.strategy.core.strategy.exit.LifecycleTrendPositionOwnershipPolicy;
import com.app.dc.strategy.core.strategy.risk.BinanceTrendEntryRiskService;
import com.app.dc.strategy.core.strategy.trend.TrendLifecycleService;
import com.app.dc.strategy.core.strategy.trend.TrendLifecycleSnapshot;
import com.app.dc.strategy.core.strategy.trend.bull.BullTrendSnapshot;
import com.app.dc.strategy.core.strategy.trend.bull.EthStructuralBullTrendService;
import com.app.dc.strategy.core.strategy.trend.bull.EthMultiTimeframeContextService;
import com.app.dc.strategy.core.strategy.trend.bull.BtcStructuralBullTrendService;
import com.app.dc.strategy.core.strategy.trend.bull.BtcMultiTimeframeContextService;
import com.app.dc.strategy.core.strategy.profile.SymbolStrategyProfileService;
import com.app.dc.strategy.core.strategy.SymbolStrategyNames;
import com.app.dc.strategy.core.strategy.trend.bull.SolMomentumBullTrendService;
import com.app.dc.strategy.core.strategy.trend.bull.SolMultiTimeframeContextService;
import com.app.dc.strategy.core.strategy.trend.bull.SolBullLaunchTrendService;
import com.app.dc.strategy.core.strategy.trend.bull.SolBullLaunchContextService;
import com.app.dc.strategy.core.strategy.trend.bear.BearTrendSnapshot;
import com.app.dc.strategy.core.strategy.trend.bear.EthStructuralBearTrendService;
import com.app.dc.strategy.core.strategy.trend.bear.EthBearMultiTimeframeContextService;
import com.app.dc.strategy.core.strategy.trend.bear.EthBearMultiTimeframeSnapshot;
import com.app.dc.strategy.core.strategy.BinanceStrategyMath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BacktestService {

    private static final DateTimeFormatter OHLC_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Autowired
    private BacktestQueryService queryService;

    @Autowired
    private BacktestSupportService supportService;

    @Autowired
    private StrategyRegistry strategyService;

    @Autowired
    private BacktestTradeService tradeService;

    @Autowired
    private BacktestMetricService metricService;

    @Autowired
    private BinanceBacktestMarketGuard marketGuard;
    @Autowired
    private DeterministicStrategyPipeline deterministicPipeline;
    @Autowired
    private StrategyPositionExitService positionExitService;
    @Autowired
    private MarketRegimeService regimeService;
    @Autowired
    private StructuralTrendService structuralTrendService;
    @Autowired
    private BinanceTrendEntryRiskService trendEntryRiskService;
    @Autowired
    private TrendLifecycleService trendLifecycleService;
    @Autowired private EthStructuralBullTrendService ethBullTrendService;
    @Autowired private EthMultiTimeframeContextService ethMultiTimeframeContextService;
    @Autowired private BtcStructuralBullTrendService btcBullTrendService;
    @Autowired private BtcMultiTimeframeContextService btcMultiTimeframeContextService;
    @Autowired private SymbolStrategyProfileService symbolStrategyProfiles;
    @Autowired private SolMomentumBullTrendService solBullTrendService;
    @Autowired private SolMultiTimeframeContextService solMultiTimeframeContextService;
    @Autowired private SolBullLaunchTrendService solBullLaunchTrendService;
    @Autowired private SolBullLaunchContextService solBullLaunchContextService;
    @Autowired private EthStructuralBearTrendService ethBearTrendService;
    @Autowired private EthBearMultiTimeframeContextService ethBearMultiTimeframeContextService;
    @Autowired private LifecycleTrendPositionOwnershipPolicy lifecyclePositionOwnership;

    public StrategyRunResponse run(BacktestParam param) throws Exception {
        BacktestParam req = normalizeParam(param);
        if ("dynamic".equalsIgnoreCase(req.strategyName)
                || "deterministic".equalsIgnoreCase(req.strategyName))
            return runDeterministicChunked(req, 2);
        List<String> symbols = supportService.resolveSymbols(req.symbols, req.symbol);

        StrategyRunResponse response = new StrategyRunResponse();
        response.symbol = symbols.size() == 1 ? symbols.get(0) : "MULTI";
        response.symbols = symbols;
        response.text = req.text;
        response.beginDate = req.beginDate;
        response.endDate = req.endDate;
        response.strategyName = req.strategyName;

        List<StrategyRunResult> results = new ArrayList<>();
        for (String symbol : symbols) {
            List<TTbookOhlc> ohlcList = queryService.queryOhlc(symbol, req.text, req.beginDate, req.endDate);
            if (ohlcList.isEmpty()) {
                continue;
            }
            BacktestParam symbolParam = copyParamForSymbol(req, symbol);
            if ("all".equalsIgnoreCase(req.strategyName)) {
                results.add(runSingleStrategy("binanceRange", symbolParam, ohlcList));
                results.add(runSingleStrategy("binanceRangeMacd", symbolParam, ohlcList));
                results.add(runSingleStrategy("binanceChannel", symbolParam, ohlcList));
                results.add(runSingleStrategy("binanceTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("ethStructuralBullTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("ethStructuralBearTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("btcBullLaunchTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("btcStructuralBearTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("solMomentumBullTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("solBullLaunchTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("solStructuralBearTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("breakoutRetestContinuationTrend", symbolParam, ohlcList));
                results.add(runSingleStrategy("emaPullbackBuy", symbolParam, ohlcList));
                results.add(runSingleStrategy("trendRestart", symbolParam, ohlcList));
                results.add(runSingleStrategy("smallRangeBreakout", symbolParam, ohlcList));
                results.add(runSingleStrategy("strongMomentumContinuation", symbolParam, ohlcList));
                results.add(runSingleStrategy("trendPullbackRecovery", symbolParam, ohlcList));
                results.add(runSingleStrategy("bollingerMeanReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("bollingerPullbackBias", symbolParam, ohlcList));
                results.add(runSingleStrategy("breakoutRetestContinuation", symbolParam, ohlcList));
                results.add(runSingleStrategy("compressionBreak", symbolParam, ohlcList));
                results.add(runSingleStrategy("failedBreakReversal", symbolParam, ohlcList));
                results.add(runSingleStrategy("impulseReclaim", symbolParam, ohlcList));
//                results.add(runSingleStrategy("rsiKdjReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("donchianReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("vwapReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("zscoreReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("atrChannelReversion", symbolParam, ohlcList));
                results.add(runSingleStrategy("atrChannelBiasReversion", symbolParam, ohlcList));
//                results.add(runSingleStrategy("orderBookImbalanceReversion", symbolParam, ohlcList));
            } else {
                results.add(runSingleStrategy(req.strategyName, symbolParam, ohlcList));
            }
        }
        response.results = results.isEmpty() ? Collections.<StrategyRunResult>emptyList() : results;
        return response;
    }

    private BacktestParam copyParamForSymbol(BacktestParam source, String symbol) {
        BacktestParam target = new BacktestParam();
        target.strategyName = source.strategyName;
        target.symbol = symbol;
        target.symbols = symbol;
        target.text = source.text;
        target.beginDate = source.beginDate;
        target.endDate = source.endDate;
        target.initialCapital = source.initialCapital;
        target.tradeNotional = source.tradeNotional;
        target.feeRatePct = source.feeRatePct;
        target.fallbackStopLossPct = source.fallbackStopLossPct;
        target.fallbackTakeProfitPct = source.fallbackTakeProfitPct;
        target.maxHoldBars = source.maxHoldBars;
        target.ignoreSentimentGuard = source.ignoreSentimentGuard;
        return target;
    }

    public StrategyRunResult runSingleStrategy(String strategyName, BacktestParam param,
                                            List<TTbookOhlc> ohlcList) throws Exception {
        SingleStrategySession session = initializeSession(strategyName, param);
        processChunk(session, ohlcList);
        return finishSession(session);
    }

    /**
     * Runs one strategy over date chunks without resetting indicators, position or equity.
     */
    public StrategyRunResponse runContinuousChunked(BacktestParam param, int chunkDays) throws Exception {
        final BacktestParam req = normalizeParam(param);
        if ("all".equalsIgnoreCase(req.strategyName)) {
            throw new IllegalArgumentException("continuous chunked backtest requires one strategy; use run() for strategy=all");
        }
        List<String> symbols = supportService.resolveSymbols(req.symbols, req.symbol);
        StrategyRunResponse response = response(req, symbols);
        List<StrategyRunResult> results = new ArrayList<StrategyRunResult>();
        for (String symbol : symbols) {
            final BacktestParam symbolParam = copyParamForSymbol(req, symbol);
            final SingleStrategySession session = initializeSession(req.strategyName, symbolParam);
            queryService.forEachOhlcChunk(symbol, req.text, req.beginDate, req.endDate, chunkDays,
                    new BacktestQueryService.OhlcChunkConsumer() {
                        @Override
                        public void accept(String begin, String end, List<TTbookOhlc> rows) throws Exception {
                            processChunk(session, rows);
                        }
                    });
            if (session.result.totalBars > 0) results.add(finishSession(session));
        }
        response.results = results;
        return response;
    }

    /** Replays Regime -> hard candidate rules -> deterministic scores -> one active strategy. */
    public StrategyRunResponse runDynamicChunked(BacktestParam param, int chunkDays) throws Exception {
        return runDeterministicChunked(param, chunkDays);
    }

    public StrategyRunResponse runDeterministicChunked(BacktestParam param, int chunkDays) throws Exception {
        final BacktestParam req = normalizeParam(param);
        req.strategyName = "deterministic";
        List<String> symbols = supportService.resolveSymbols(req.symbols, req.symbol);
        StrategyRunResponse response = response(req, symbols);
        response.routingDecisions = new ArrayList<StrategyRoutingDecision>();
        response.routingStats = new StrategyRuntimeModels.RoutingStats();
        List<StrategyRunResult> results = new ArrayList<StrategyRunResult>();
        for (String symbol : symbols) {
            final BacktestParam symbolParam = copyParamForSymbol(req, symbol);
            final DeterministicSession session = initializeDeterministic(symbolParam);
            queryService.forEachOhlcChunk(symbol, req.text, req.beginDate, req.endDate, chunkDays,
                    new BacktestQueryService.OhlcChunkConsumer() {
                        @Override
                        public void accept(String begin, String end, List<TTbookOhlc> rows) throws Exception {
                            processDeterministicChunk(session, rows);
                        }
                    });
            if (session.result.totalBars > 0) {
                results.add(finishDeterministic(session));
                response.routingDecisions.addAll(session.decisions);
            }
            mergeRoutingStats(response.routingStats, session.stats);
        }
        response.results = results;
        return response;
    }

    private void mergeRoutingStats(StrategyRuntimeModels.RoutingStats target, StrategyRuntimeModels.RoutingStats source) {
        target.routingDecisionCount += source.routingDecisionCount;
        mergeCounts(target.routingReasonCounts, source.routingReasonCounts);
        mergeCounts(target.selectedStrategyCounts, source.selectedStrategyCounts);
        mergeCounts(target.routeOwnerStrategyCounts, source.routeOwnerStrategyCounts);
        mergeCounts(target.setupBlockedStrategyCounts, source.setupBlockedStrategyCounts);
        mergeCounts(target.positionRunningStrategyCounts, source.positionRunningStrategyCounts);
        mergeCounts(target.regimeCounts, source.regimeCounts);
        mergeCounts(target.candidateAcceptedCounts, source.candidateAcceptedCounts);
        mergeCounts(target.candidateRejectReasonCounts, source.candidateRejectReasonCounts);
        mergeCounts(target.signalCounts, source.signalCounts);
        mergeCounts(target.strategySignalCounts, source.strategySignalCounts);
        mergeCounts(target.participationBlockReasonCounts, source.participationBlockReasonCounts);
        mergeCounts(target.atrChannelPhaseCounts, source.atrChannelPhaseCounts);
        mergeCounts(target.atrChannelReasonCounts, source.atrChannelReasonCounts);
        mergeCounts(target.strategyTradeCounts, source.strategyTradeCounts);
        mergeCounts(target.structuralTrendCounts, source.structuralTrendCounts);
        mergeCounts(target.structuralPhaseCounts, source.structuralPhaseCounts);
        mergeCounts(target.signalSourceCounts, source.signalSourceCounts);
        mergeCounts(target.structuralScoreAdjustmentCounts, source.structuralScoreAdjustmentCounts);
        mergeCounts(target.trendCompressionPhaseCounts, source.trendCompressionPhaseCounts);
        mergeCounts(target.trendCompressionDirectionCounts, source.trendCompressionDirectionCounts);
        mergeCounts(target.trendLifecyclePhaseCounts,source.trendLifecyclePhaseCounts);
        mergeCounts(target.trendLifecycleReasonCounts,source.trendLifecycleReasonCounts);
        mergeCounts(target.ethBullTrendPhaseCounts,source.ethBullTrendPhaseCounts);
        mergeCounts(target.ethBullTrendReasonCounts,source.ethBullTrendReasonCounts);
        mergeCounts(target.btcBullTrendPhaseCounts,source.btcBullTrendPhaseCounts);
        mergeCounts(target.btcBullTrendReasonCounts,source.btcBullTrendReasonCounts);
        mergeCounts(target.solBullTrendPhaseCounts,source.solBullTrendPhaseCounts);
        mergeCounts(target.solBullTrendReasonCounts,source.solBullTrendReasonCounts);
        mergeCounts(target.solBullLaunchTrendPhaseCounts,source.solBullLaunchTrendPhaseCounts);
        mergeCounts(target.solBullLaunchTrendReasonCounts,source.solBullLaunchTrendReasonCounts);
        mergeCounts(target.ethBearTrendPhaseCounts,source.ethBearTrendPhaseCounts);
        mergeCounts(target.ethBearTrendReasonCounts,source.ethBearTrendReasonCounts);
        mergeCounts(target.solBearTrendPhaseCounts,source.solBearTrendPhaseCounts);
        mergeCounts(target.solBearTrendReasonCounts,source.solBearTrendReasonCounts);
        mergeCounts(target.btcBullLaunchTrendPhaseCounts,source.btcBullLaunchTrendPhaseCounts);
        mergeCounts(target.btcBullLaunchTrendReasonCounts,source.btcBullLaunchTrendReasonCounts);
        mergeCounts(target.btcBearTrendPhaseCounts,source.btcBearTrendPhaseCounts);
        mergeCounts(target.btcBearTrendReasonCounts,source.btcBearTrendReasonCounts);
    }

    private void mergeCounts(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            Integer current = target.get(entry.getKey());
            target.put(entry.getKey(), (current == null ? 0 : current) + entry.getValue());
        }
    }

    private void increment(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, current == null ? 1 : current + 1);
    }

    private DeterministicSession initializeDeterministic(BacktestParam param) {
        Duration duration = supportService.resolveDuration(param.text);
        BarSeries series = new BaseBarSeries(param.symbol + "-" + param.text + "-deterministic");
        StrategyRunResult result = initResult("deterministic", param);
        EquityContext equity = metricService.initEquityContext(param.initialCapital.doubleValue(),
                param.tradeNotional.doubleValue());
        BinanceBacktestMarketGuard.GuardContext guard = marketGuard.prepareContext(param.symbol, param.beginDate, param.endDate);
        strategyService.resetAll(param.symbol);
        String ethBearStrategy=SymbolStrategyNames.qualify("ethStructuralBearTrend",param.symbol);
        String ethBullStrategy=SymbolStrategyNames.qualify("ethStructuralBullTrend",param.symbol);
        String btcBullStrategy=SymbolStrategyNames.qualify("btcStructuralBullTrend",param.symbol);
        String btcLaunchStrategy=SymbolStrategyNames.qualify("btcBullLaunchTrend",param.symbol);
        String btcBearStrategy=SymbolStrategyNames.qualify("btcStructuralBearTrend",param.symbol);
        boolean bearEnabled="ETHUSDT".equalsIgnoreCase(param.symbol)
                &&symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,ethBearStrategy);
        boolean btcBearEnabled="BTCUSDT".equalsIgnoreCase(param.symbol)
                &&symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,btcBearStrategy);
        if(symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,ethBullStrategy)||bearEnabled)
            prepareEthMultiTimeframe(param,bearEnabled);
        if(symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,btcBullStrategy))
            prepareBtcStructuralBullMultiTimeframe(param);
        if(btcBearEnabled)prepareEthMultiTimeframe(param,true);
        if("BTCUSDT".equalsIgnoreCase(param.symbol)
                &&symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,btcLaunchStrategy))
            prepareBtcLaunchMultiTimeframe(param);
        String solBullStrategy=SymbolStrategyNames.qualify("solMomentumBullTrend",param.symbol);
        String solLaunchStrategy=SymbolStrategyNames.qualify("solBullLaunchTrend",param.symbol);
        String solBearStrategy=SymbolStrategyNames.qualify("solStructuralBearTrend",param.symbol);
        boolean solBearEnabled=symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,solBearStrategy);
        if(symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,solBullStrategy)
                ||symbolStrategyProfiles.isStrategyEnabled(param.symbol,param.text,solLaunchStrategy)
                ||solBearEnabled)
            prepareSolMultiTimeframe(param,solBearEnabled);
        return new DeterministicSession(param, duration, series, result, equity, guard,
                deterministicPipeline.newState());
    }

    private void processDeterministicChunk(DeterministicSession session, List<TTbookOhlc> rows) throws Exception {
        if (rows == null) return;
        for (TTbookOhlc ohlc : rows) {
            Bar bar = toBar(ohlc, session.duration);
            boolean firstVersionOfBar = addBar(session.series, bar);
            // Duplicate ClickHouse view rows may update the BarSeries, but must not
            // execute regime, routing, risk checks or strategy signals twice.
            if (!firstVersionOfBar) continue;
            int index = session.series.getEndIndex();
            session.result.totalBars = session.series.getBarCount();
            updateActualCoverage(session.result, session.series);
            if (session.position != null && session.position.entryIndex < index) {
                TradeRecord closed = tradeService.tryCloseByRisk(session.position, bar, index, session.param.feeRatePct.doubleValue());
                if (closed != null) {
                    applyClosedTrade(session.result, closed, session.equity, session.param.symbol, index);
                    session.position = null;
                }
            }
            DeterministicPipelineResult pipelineResult = deterministicPipeline.onClosedBar(
                    session.routerState, session.param.symbol, session.param.text, session.series, ohlc,
                    session.position==null?null:session.position.strategyName);
            StrategyRoutingDecision decision = pipelineResult.routingDecision;
            if (shouldRecordRoutingDecision(decision, pipelineResult.signal))
                session.decisions.add(decision);
            recordRouting(session.stats, pipelineResult);
            if (session.position != null) {
                PositionExitDecision exit = positionExitService.evaluate(session.position,
                        new StrategyPositionExitContext(session.series,
                                pipelineResult.context.regime, pipelineResult.structuralTrend,
                                pipelineResult.trendLifecycle,session.param.symbol,session.param.text));
                if (exit.exit) {
                    TradeRecord invalidated = tradeService.closePosition(session.position,
                            bar.getClosePrice().doubleValue(), bar.getEndTime().toString(),
                            exit.reason, index, session.param.feeRatePct.doubleValue());
                    applyClosedTrade(session.result, invalidated, session.equity,
                            session.param.symbol, index);
                    session.position = null;
                    continue;
                }
            }
            Signal signal = pipelineResult.signal;
            String executionStrategy = pipelineResult.executionStrategyName;
            if (executionStrategy == null || signal == null || signal.side == null || signal.side == Side.NONE) continue;
            trendEntryRiskService.apply(executionStrategy, session.param.symbol,
                    session.param.text, signal, session.series,
                    pipelineResult.structuralTrend);
            if (marketGuard.shouldBlock(executionStrategy, session.guard, bar.getEndTime().toInstant(), Boolean.TRUE.equals(session.param.ignoreSentimentGuard)))
                continue;
            if (session.position == null) {
                String rejection = tradeService.validateOpenSignal(signal, session.param);
                if (rejection != null) {
                    incrementReject(session.result, rejection);
                    continue;
                }
                session.position = tradeService.openPosition(signal, index, bar, session.param);
                session.position.strategyName = executionStrategy;
                session.position.regime = executionRegime(pipelineResult);
                decorateTrendPosition(session.position,executionStrategy,session.param.symbol,session.param.text,pipelineResult.trendLifecycle,signal,session.series);
                strategyService.onTradeOpened(executionStrategy,session.param.symbol,session.param.text,index);
                increment(session.stats.strategyTradeCounts, executionStrategy);
            } else if (tradeService.isOpposite(session.position.side, signal.side)) {
                if(lifecyclePositionOwnership.blocksForeignReversal(session.position,executionStrategy)){
                    incrementReject(session.result,LifecycleTrendPositionOwnershipPolicy.REJECTION_REASON);
                    continue;
                }
                String rejection = tradeService.validateOpenSignal(signal, session.param);
                if (rejection != null) {
                    incrementReject(session.result, rejection);
                    continue;
                }
                TradeRecord reversed = tradeService.closePosition(session.position, bar.getClosePrice().doubleValue(), bar.getEndTime().toString(), "reverse_signal", index, session.param.feeRatePct.doubleValue());
                applyClosedTrade(session.result, reversed, session.equity, session.param.symbol, index);
                session.position = tradeService.openPosition(signal, index, bar, session.param);
                session.position.strategyName = executionStrategy;
                session.position.regime = executionRegime(pipelineResult);
                decorateTrendPosition(session.position,executionStrategy,session.param.symbol,session.param.text,pipelineResult.trendLifecycle,signal,session.series);
                strategyService.onTradeOpened(executionStrategy,session.param.symbol,session.param.text,index);
                increment(session.stats.strategyTradeCounts, executionStrategy);
            } else if (lifecyclePositionOwnership.shouldHandoffSameDirection(
                    session.position,executionStrategy,signal.side)) {
                String rejection = tradeService.validateOpenSignal(signal, session.param);
                if (rejection != null) {
                    incrementReject(session.result, rejection);
                    continue;
                }
                TradeRecord handedOff = tradeService.closePosition(session.position,
                        bar.getClosePrice().doubleValue(),bar.getEndTime().toString(),
                        "lifecycle_strategy_handoff",index,session.param.feeRatePct.doubleValue());
                applyClosedTrade(session.result,handedOff,session.equity,session.param.symbol,index);
                session.position=tradeService.openPosition(signal,index,bar,session.param);
                session.position.strategyName=executionStrategy;
                session.position.regime=executionRegime(pipelineResult);
                decorateTrendPosition(session.position,executionStrategy,session.param.symbol,
                        session.param.text,pipelineResult.trendLifecycle,signal,session.series);
                strategyService.onTradeOpened(executionStrategy,session.param.symbol,session.param.text,index);
                increment(session.stats.strategyTradeCounts,executionStrategy);
            }
        }
    }

    /**
     * Full routing statistics are accumulated for every bar.  The report audit list only keeps
     * state transitions, confirmation progress and actionable signals; retaining the complete
     * score/rejection maps for every bar makes multi-year streaming replays needlessly unbounded.
     */
    private boolean shouldRecordRoutingDecision(StrategyRoutingDecision decision, Signal signal) {
        if (decision == null) return false;
        if (signal != null && signal.side != null && signal.side != Side.NONE) return true;
        if (!sameStrategy(decision.previousStrategyName, decision.strategyName)) return true;
        String reason = decision.reason == null ? "" : decision.reason;
        return "ACTIVATED".equals(reason) || "SWITCHED".equals(reason)
                || "LOW_SCORE_SWITCHED".equals(reason) || "LOW_SCORE_EXIT".equals(reason)
                || "HARD_INVALID_SWITCHED".equals(reason)
                || "LIFECYCLE_TRIGGER_PRIORITY".equals(reason)
                || reason.contains("AWAITING_CONFIRMATION");
    }

    private boolean sameStrategy(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private String executionRegime(DeterministicPipelineResult result) {
        return result.routingDecision == null ? null : result.routingDecision.regime;
    }

    private void recordRouting(StrategyRuntimeModels.RoutingStats stats, DeterministicPipelineResult result) {
        StrategyRoutingDecision decision = result.routingDecision;
        stats.routingDecisionCount++;
        increment(stats.routingReasonCounts, result.participationBlocked
                ? "MARKET_PARTICIPATION_BLOCKED"
                : decision.reason == null ? "UNKNOWN" : decision.reason);
        increment(stats.routeOwnerStrategyCounts,
                decision.strategyName == null ? "NO_OWNER" : decision.strategyName);
        increment(stats.selectedStrategyCounts, result.participationBlocked
                || decision.strategyName == null ? "NO_TRADE" : decision.strategyName);
        increment(stats.regimeCounts, decision.regime == null ? "UNKNOWN" : decision.regime);
        for (com.app.dc.strategy.core.dynamic.DynamicStrategyMeta candidate : result.candidates.candidates)
            increment(stats.candidateAcceptedCounts, candidate.strategyName);
        for (String reason : decision.candidateRejections.values()) increment(stats.candidateRejectReasonCounts, reason);
        String side = result.signal == null || result.signal.side == null || result.signal.side == Side.NONE
                ? "HOLD" : result.signal.side.name();
        increment(stats.signalCounts, side);
        String executionStrategy = result.participationBlocked ? null
                : result.executionStrategyName == null
                ? decision.strategyName : result.executionStrategyName;
        if (executionStrategy != null) increment(stats.strategySignalCounts, executionStrategy + ":" + side);
        if (result.participationBlocked)
            increment(stats.participationBlockReasonCounts,
                    result.participationBlockReason == null ? "UNKNOWN" : result.participationBlockReason);
        if (result.participationBlocked && decision.strategyName != null) {
            if ("POSITION_RUNNING".equals(result.participationBlockReason))
                increment(stats.positionRunningStrategyCounts, decision.strategyName);
            else increment(stats.setupBlockedStrategyCounts, decision.strategyName);
        }
        if (result.atrChannelBiasSetup != null) {
            increment(stats.atrChannelPhaseCounts, result.atrChannelBiasSetup.phase);
            increment(stats.atrChannelReasonCounts, result.atrChannelBiasSetup.reason);
        }
        if (result.structuralTrend != null) {
            increment(stats.structuralTrendCounts, result.structuralTrend.direction);
            increment(stats.structuralPhaseCounts, result.structuralTrend.phase);
        }
        increment(stats.signalSourceCounts, result.signalSource == null ? "NONE" : result.signalSource);
        String adjustment = decision.structuralScoreAdjustment > 0 ? "BONUS"
                : decision.structuralScoreAdjustment < 0 ? "PENALTY" : "NEUTRAL";
        increment(stats.structuralScoreAdjustmentCounts, adjustment);
        if (result.context != null && result.context.trendCompression != null) {
            increment(stats.trendCompressionPhaseCounts,
                    result.context.trendCompression.phase);
            increment(stats.trendCompressionDirectionCounts,
                    result.context.trendCompression.direction);
        }
        if(result.trendLifecycle!=null){
            increment(stats.trendLifecyclePhaseCounts,result.trendLifecycle.phase);
            increment(stats.trendLifecycleReasonCounts,result.trendLifecycle.reason);
        }
        if(result.ethBullTrend!=null){
            increment(stats.ethBullTrendPhaseCounts,result.ethBullTrend.phase);
            increment(stats.ethBullTrendReasonCounts,result.ethBullTrend.reason);
        }
        if(result.solBullTrend!=null){
            increment(stats.solBullTrendPhaseCounts,result.solBullTrend.phase);
            increment(stats.solBullTrendReasonCounts,result.solBullTrend.reason);
        }
        if(result.btcBullTrend!=null){
            increment(stats.btcBullTrendPhaseCounts,result.btcBullTrend.phase);
            increment(stats.btcBullTrendReasonCounts,result.btcBullTrend.reason);
        }
        if(result.solBullLaunchTrend!=null){
            increment(stats.solBullLaunchTrendPhaseCounts,result.solBullLaunchTrend.phase);
            increment(stats.solBullLaunchTrendReasonCounts,result.solBullLaunchTrend.reason);
        }
        if(result.ethBearTrend!=null){
            increment(stats.ethBearTrendPhaseCounts,result.ethBearTrend.phase);
            increment(stats.ethBearTrendReasonCounts,result.ethBearTrend.reason);
        }
        if(result.solBearTrend!=null){
            increment(stats.solBearTrendPhaseCounts,result.solBearTrend.phase);
            increment(stats.solBearTrendReasonCounts,result.solBearTrend.reason);
        }
        if(result.btcBullLaunchTrend!=null){
            increment(stats.btcBullLaunchTrendPhaseCounts,result.btcBullLaunchTrend.phase);
            increment(stats.btcBullLaunchTrendReasonCounts,result.btcBullLaunchTrend.reason);
        }
        if(result.btcBearTrend!=null){
            increment(stats.btcBearTrendPhaseCounts,result.btcBearTrend.phase);
            increment(stats.btcBearTrendReasonCounts,result.btcBearTrend.reason);
        }
    }

    private StrategyRunResult finishDeterministic(DeterministicSession session) {
        if (session.position != null && session.series.getBarCount() > 0) {
            Bar last = session.series.getLastBar();
            TradeRecord closed = tradeService.closePosition(session.position, last.getClosePrice().doubleValue(), last.getEndTime().toString(), "end_of_test", session.series.getEndIndex(), session.param.feeRatePct.doubleValue());
            applyClosedTrade(session.result, closed, session.equity,
                    session.param.symbol, session.series.getEndIndex());
            session.position = null;
        }
        metricService.finishResult(session.result, session.equity);
        mergeRejectStats(session.result, strategyService.snapshotAllRejectStats(session.param.symbol));
        return session.result;
    }

    private static class DeterministicSession {
        final BacktestParam param;
        final Duration duration;
        final BarSeries series;
        final StrategyRunResult result;
        final EquityContext equity;
        final BinanceBacktestMarketGuard.GuardContext guard;
        final DeterministicPipelineState routerState;
        final List<StrategyRoutingDecision> decisions = new ArrayList<StrategyRoutingDecision>();
        final StrategyRuntimeModels.RoutingStats stats = new StrategyRuntimeModels.RoutingStats();
        Position position;

        DeterministicSession(BacktestParam p, Duration d, BarSeries s, StrategyRunResult r, EquityContext e,
                             BinanceBacktestMarketGuard.GuardContext g, DeterministicPipelineState state) {
            param = p;
            duration = d;
            series = s;
            result = r;
            equity = e;
            guard = g;
            routerState = state;
        }
    }

    private StrategyRunResponse response(BacktestParam req, List<String> symbols) {
        StrategyRunResponse r = new StrategyRunResponse();
        r.symbol = symbols.size() == 1 ? symbols.get(0) : "MULTI";
        r.symbols = symbols;
        r.text = req.text;
        r.beginDate = req.beginDate;
        r.endDate = req.endDate;
        r.strategyName = req.strategyName;
        return r;
    }

    private SingleStrategySession initializeSession(String strategyName, BacktestParam param) throws Exception {
        String normalizedStrategy = supportService.normalizeStrategyName(strategyName);
        Duration duration = supportService.resolveDuration(param.text);
        BarSeries replaySeries = new BaseBarSeries(param.symbol + "-" + param.text + "-" + normalizedStrategy);
        strategyService.getStrategy(normalizedStrategy).resetSession(param.symbol);
        String baseStrategy=SymbolStrategyNames.baseName(normalizedStrategy);
        if("ethStructuralBullTrend".equalsIgnoreCase(baseStrategy)
                ||"btcBullLaunchTrend".equalsIgnoreCase(baseStrategy)
                ||"ethStructuralBearTrend".equalsIgnoreCase(baseStrategy)
                ||"btcStructuralBearTrend".equalsIgnoreCase(baseStrategy))
            prepareEthMultiTimeframe(param,"ethStructuralBearTrend".equalsIgnoreCase(baseStrategy)
                    ||"btcStructuralBearTrend".equalsIgnoreCase(baseStrategy));
        if("btcStructuralBullTrend".equalsIgnoreCase(baseStrategy))prepareBtcStructuralBullMultiTimeframe(param);
        if("btcBullLaunchTrend".equalsIgnoreCase(baseStrategy))prepareBtcLaunchMultiTimeframe(param);
        if("solMomentumBullTrend".equalsIgnoreCase(baseStrategy)
                ||"solBullLaunchTrend".equalsIgnoreCase(baseStrategy))prepareSolMultiTimeframe(param);
        if("solStructuralBearTrend".equalsIgnoreCase(baseStrategy))prepareSolMultiTimeframe(param,true);

        StrategyRunResult result = initResult(normalizedStrategy, param);
        EquityContext equityContext = metricService.initEquityContext(param.initialCapital.doubleValue(),
                param.tradeNotional.doubleValue());
        BinanceBacktestMarketGuard.GuardContext guardContext =
                marketGuard.prepareContext(param.symbol, param.beginDate, param.endDate);
        return new SingleStrategySession(normalizedStrategy, param, duration, replaySeries, result,
                equityContext, guardContext, structuralTrendService.newState());
    }

    private void processChunk(SingleStrategySession session, List<TTbookOhlc> ohlcList) throws Exception {
        if (ohlcList == null) return;
        for (TTbookOhlc ohlc : ohlcList) {
            Bar bar = toBar(ohlc, session.duration);
            addBar(session.replaySeries, bar);
            session.result.totalBars = session.replaySeries.getBarCount();
            updateActualCoverage(session.result, session.replaySeries);

            if (session.position != null && session.position.entryIndex < session.replaySeries.getEndIndex()) {
                TradeRecord riskClosed = tradeService.tryCloseByRisk(session.position, bar, session.replaySeries.getEndIndex(),
                        session.param.feeRatePct.doubleValue());
                if (riskClosed != null) {
                    applyClosedTrade(session.result, riskClosed, session.equityContext,
                            session.param.symbol, session.replaySeries.getEndIndex());
                    session.position = null;
                }
            }

            MarketRegime currentRegime = regimeService.identify(session.replaySeries);
            StructuralTrendSnapshot structural = structuralTrendService.update(
                    session.structuralTrendState, session.replaySeries);
            TrendLifecycleSnapshot lifecycle=trendLifecycleService.update(
                    session.param.symbol,session.param.text,session.replaySeries,structural,currentRegime);
            ethBullTrendService.update(session.param.symbol,session.param.text,
                    session.replaySeries,structural,currentRegime);
            btcBullTrendService.update(session.param.symbol,session.param.text,
                    session.replaySeries,structural,currentRegime);
            solBullTrendService.update(session.param.symbol,session.param.text,
                    session.replaySeries,structural,currentRegime);
            solBullLaunchTrendService.update(session.param.symbol,session.param.text,
                    session.replaySeries,structural,currentRegime);
            ethBearTrendService.update(session.param.symbol,session.param.text,
                    session.replaySeries,structural,currentRegime);
            if (session.position != null) {
                PositionExitDecision exit = positionExitService.evaluate(session.position,
                        new StrategyPositionExitContext(session.replaySeries,currentRegime,structural,
                                lifecycle,session.param.symbol,session.param.text));
                if (exit.exit) {
                    TradeRecord invalidated = tradeService.closePosition(session.position,
                            bar.getClosePrice().doubleValue(), bar.getEndTime().toString(), exit.reason,
                            session.replaySeries.getEndIndex(), session.param.feeRatePct.doubleValue());
                    applyClosedTrade(session.result, invalidated, session.equityContext,
                            session.param.symbol, session.replaySeries.getEndIndex());
                    session.position = null;
                    continue;
                }
            }

            Signal signal = strategyService.evaluateSignal(session.normalizedStrategy, session.param.symbol, session.param.text, session.replaySeries, ohlc);
            // NONE 表示无信号，不开仓也不反手。
            if (signal.side == null || signal.side == Side.NONE) {
                continue;
            }
            trendEntryRiskService.apply(session.normalizedStrategy, session.param.symbol,
                    session.param.text, signal,
                    session.replaySeries, structural);
            boolean ignoreSentimentGuard = Boolean.TRUE.equals(session.param.ignoreSentimentGuard);
            if (marketGuard.shouldBlock(session.normalizedStrategy, session.guardContext, bar.getEndTime().toInstant(), ignoreSentimentGuard)) {
                continue;
            }

            if (session.position == null) {
                String rejection = tradeService.validateOpenSignal(signal, session.param);
                if (rejection != null) {
                    incrementReject(session.result, rejection);
                    continue;
                }
                session.position = tradeService.openPosition(signal, session.replaySeries.getEndIndex(), bar, session.param);
                session.position.strategyName = session.normalizedStrategy;
                decorateTrendPosition(session.position,session.normalizedStrategy,session.param.symbol,session.param.text,lifecycle,signal,session.replaySeries);
                strategyService.onTradeOpened(session.normalizedStrategy,session.param.symbol,
                        session.param.text,session.replaySeries.getEndIndex());
                continue;
            }

            if (tradeService.isOpposite(session.position.side, signal.side)) {
                String rejection = tradeService.validateOpenSignal(signal, session.param);
                if (rejection != null) {
                    incrementReject(session.result, rejection);
                    continue;
                }
                TradeRecord reversed = tradeService.closePosition(session.position, bar.getClosePrice().doubleValue(),
                        bar.getEndTime().toString(), "reverse_signal", session.replaySeries.getEndIndex(),
                        session.param.feeRatePct.doubleValue());
                applyClosedTrade(session.result, reversed, session.equityContext,
                        session.param.symbol, session.replaySeries.getEndIndex());
                session.position = tradeService.openPosition(signal, session.replaySeries.getEndIndex(), bar, session.param);
                session.position.strategyName = session.normalizedStrategy;
                decorateTrendPosition(session.position,session.normalizedStrategy,session.param.symbol,session.param.text,lifecycle,signal,session.replaySeries);
                strategyService.onTradeOpened(session.normalizedStrategy,session.param.symbol,
                        session.param.text,session.replaySeries.getEndIndex());
            }
        }
    }

    private void decorateTrendPosition(Position position,String strategy,String symbol,String timeframe,
                                       TrendLifecycleSnapshot lifecycle,Signal signal,BarSeries series){
        if(position==null)return;
        String baseStrategy=SymbolStrategyNames.baseName(strategy);
        if("binanceTrend".equalsIgnoreCase(baseStrategy)&&lifecycle!=null)
            position.entryLifecyclePhase=lifecycle.phase;
        else if("ethStructuralBullTrend".equalsIgnoreCase(baseStrategy)){
            position.entryLifecyclePhase="TRIGGERED";
            position.ethSoftStopPrice=ethBullTrendService.currentSoftStop(symbol,timeframe);
        }
        else if("btcStructuralBullTrend".equalsIgnoreCase(baseStrategy)){
            position.entryLifecyclePhase="TRIGGERED";
            position.btcSoftStopPrice=btcBullTrendService.currentSoftStop(symbol,timeframe);
        }
        else if("solMomentumBullTrend".equalsIgnoreCase(baseStrategy)){
            position.entryLifecyclePhase="TRIGGERED";
            position.solSoftStopPrice=solBullTrendService.currentSoftStop(symbol,timeframe);
        }
        else if("solBullLaunchTrend".equalsIgnoreCase(baseStrategy)){
            position.entryLifecyclePhase="TRIGGERED";
            position.solSoftStopPrice=solBullLaunchTrendService.currentSoftStop(symbol,timeframe);
        }
        else if("btcBullLaunchTrend".equalsIgnoreCase(baseStrategy)){
            position.entryLifecyclePhase="TRIGGERED";
            position.ethSoftStopPrice=solBullLaunchTrendService.currentSoftStop(symbol,timeframe);
        }
        else if("ethStructuralBearTrend".equalsIgnoreCase(baseStrategy)
                ||"solStructuralBearTrend".equalsIgnoreCase(baseStrategy)
                ||"btcStructuralBearTrend".equalsIgnoreCase(baseStrategy)){
            EthBearMultiTimeframeSnapshot bear=ethBearMultiTimeframeContextService.current(symbol);
            position.entryLifecyclePhase="TRIGGERED|1D_"+bear.dailyState+"|4H_"+bear.fourHourTrend
                    +"|1H_"+bear.oneHourPhase;
            String context="1D_"+bear.dailyState+"|4H_"+bear.fourHourTrend+"|1H_"+bear.oneHourPhase;
            position.regime=position.regime==null||position.regime.length()==0?context:position.regime+"|"+context;
            position.ethBearSoftStopPrice=ethBearTrendService.currentSoftStop(symbol,timeframe);
        }
        else if("binanceChannel".equalsIgnoreCase(baseStrategy)
                &&"ETHUSDT".equalsIgnoreCase(symbol)&&"15M".equalsIgnoreCase(timeframe)){
            position.entryLifecyclePhase="ETH_CHANNEL_BREAKOUT";
            int end=series==null?-1:series.getEndIndex();
            if(end>series.getBeginIndex())position.channelBreakoutLevel=
                    BinanceStrategyMath.highestHigh(series,end-1,20);
        }
        else return;
        position.trendTriggerType=signal==null?null:signal.remark;
        position.entryAtr=series==null?Double.NaN:BinanceStrategyMath.atr(
                series,series.getEndIndex(),14);
    }

    private void prepareEthMultiTimeframe(BacktestParam param,boolean prepareBear){
        if(param==null||!("ETHUSDT".equalsIgnoreCase(param.symbol)
                ||"BTCUSDT".equalsIgnoreCase(param.symbol))||!"15M".equalsIgnoreCase(param.text))return;
        String contextBegin="BTCUSDT".equalsIgnoreCase(param.symbol)
                ?LocalDate.parse(param.beginDate).minusDays(120).toString():param.beginDate;
        List<TTbookOhlc> oneHour=queryService.queryLocalOhlc(param.symbol,"1h",contextBegin,param.endDate);
        List<TTbookOhlc> fourHour=queryService.queryLocalOhlc(param.symbol,"4h",contextBegin,param.endDate);
        ethMultiTimeframeContextService.prepare(param.symbol,oneHour,fourHour);
        if(prepareBear){
            List<TTbookOhlc> daily=queryService.queryLocalOhlc(param.symbol,"1d",param.beginDate,param.endDate);
            ethBearMultiTimeframeContextService.prepare(param.symbol,daily,oneHour,fourHour);
        }else ethBearMultiTimeframeContextService.prepare(param.symbol,oneHour,fourHour);
    }

    private void prepareBtcStructuralBullMultiTimeframe(BacktestParam param){
        if(param==null||!"BTCUSDT".equalsIgnoreCase(param.symbol)||!"15M".equalsIgnoreCase(param.text))return;
        String begin=LocalDate.parse(param.beginDate).minusDays(120).toString();
        List<TTbookOhlc> oneHour=queryService.queryLocalOhlc(param.symbol,"1h",begin,param.endDate);
        List<TTbookOhlc> fourHour=queryService.queryLocalOhlc(param.symbol,"4h",begin,param.endDate);
        btcMultiTimeframeContextService.prepare(param.symbol,oneHour,fourHour);
    }

    private void prepareSolMultiTimeframe(BacktestParam param){
        prepareSolMultiTimeframe(param,false);
    }

    private void prepareBtcLaunchMultiTimeframe(BacktestParam param){
        if(param==null||!"BTCUSDT".equalsIgnoreCase(param.symbol)||!"15M".equalsIgnoreCase(param.text))return;
        String begin=LocalDate.parse(param.beginDate).minusDays(120).toString();
        List<TTbookOhlc> oneHour=queryService.queryLocalOhlc(param.symbol,"1h",begin,param.endDate);
        List<TTbookOhlc> fourHour=queryService.queryLocalOhlc(param.symbol,"4h",begin,param.endDate);
        solBullLaunchContextService.prepare(param.symbol,oneHour,fourHour);
    }

    private void prepareSolMultiTimeframe(BacktestParam param,boolean prepareBear){
        if(param==null||!"SOLUSDT".equalsIgnoreCase(param.symbol)
                ||!"15M".equalsIgnoreCase(param.text))return;
        List<TTbookOhlc> oneHour=queryService.queryLocalOhlc(
                param.symbol,"1h",param.beginDate,param.endDate);
        List<TTbookOhlc> fourHour=queryService.queryLocalOhlc(
                param.symbol,"4h",param.beginDate,param.endDate);
        solMultiTimeframeContextService.prepare(param.symbol,oneHour,fourHour);
        solBullLaunchContextService.prepare(param.symbol,oneHour,fourHour);
        if(prepareBear){
            List<TTbookOhlc> daily=queryService.queryLocalOhlc(
                    param.symbol,"1d",param.beginDate,param.endDate);
            ethBearMultiTimeframeContextService.prepare(param.symbol,daily,oneHour,fourHour);
        }
    }

    private StrategyRunResult finishSession(SingleStrategySession session) {
        if (session.position != null && session.replaySeries.getBarCount() > 0) {
            Bar lastBar = session.replaySeries.getLastBar();
            TradeRecord ended = tradeService.closePosition(session.position, lastBar.getClosePrice().doubleValue(),
                    lastBar.getEndTime().toString(), "end_of_test", session.replaySeries.getEndIndex(),
                    session.param.feeRatePct.doubleValue());
            applyClosedTrade(session.result, ended, session.equityContext,
                    session.param.symbol, session.replaySeries.getEndIndex());
            session.position = null;
        }

        metricService.finishResult(session.result, session.equityContext);
        Map<String, Integer> strategyRejects = strategyService.getStrategy(session.normalizedStrategy)
                .snapshotRejectStats(session.param.symbol);
        mergeRejectStats(session.result, strategyRejects);
        return session.result;
    }

    private void applyClosedTrade(StrategyRunResult result, TradeRecord trade, EquityContext equity,
                                  String symbol, int exitBarIndex) {
        metricService.applyTrade(result, trade, equity);
        strategyService.onTradeClosed(trade.strategyName, symbol, exitBarIndex, trade);
    }

    private void mergeRejectStats(StrategyRunResult result, Map<String, Integer> strategyRejects) {
        if (strategyRejects == null) return;
        for (Map.Entry<String, Integer> entry : strategyRejects.entrySet()) {
            Integer previous = result.rejectReasonCounts.get(entry.getKey());
            result.rejectReasonCounts.put(entry.getKey(), (previous == null ? 0 : previous)
                    + (entry.getValue() == null ? 0 : entry.getValue()));
        }
    }

    private void updateActualCoverage(StrategyRunResult result, BarSeries series) {
        if (result == null || series == null || series.getBarCount() == 0) {
            return;
        }
        result.actualBeginTime = series.getFirstBar().getEndTime().toString();
        result.actualEndTime = series.getLastBar().getEndTime().toString();
    }

    private void incrementReject(StrategyRunResult result, String reason) {
        if (result.rejectReasonCounts == null) {
            result.rejectReasonCounts = new LinkedHashMap<String, Integer>();
        }
        Integer count = result.rejectReasonCounts.get(reason);
        result.rejectReasonCounts.put(reason, count == null ? 1 : count + 1);
    }

    private static class SingleStrategySession {
        final String normalizedStrategy;
        final BacktestParam param;
        final Duration duration;
        final BarSeries replaySeries;
        final StrategyRunResult result;
        final EquityContext equityContext;
        final BinanceBacktestMarketGuard.GuardContext guardContext;
        final StructuralTrendState structuralTrendState;
        Position position;

        SingleStrategySession(String n, BacktestParam p, Duration d, BarSeries s,
                              StrategyRunResult r, EquityContext e,
                              BinanceBacktestMarketGuard.GuardContext g,
                              StructuralTrendState structuralState) {
            normalizedStrategy = n;
            param = p;
            duration = d;
            replaySeries = s;
            result = r;
            equityContext = e;
            guardContext = g;
            structuralTrendState = structuralState;
        }
    }

    public BacktestParam normalizeParam(BacktestParam param) {
        BacktestParam req = param == null ? new BacktestParam() : param;
        if (req.symbol == null || req.symbol.trim().isEmpty()) {
            req.symbol = "ETHUSDT";
        }
        if (req.symbols == null || req.symbols.trim().isEmpty()) {
            req.symbols = req.symbol;
        }
        if (req.text == null || req.text.trim().isEmpty()) {
            req.text = "15m";
        }
        req.text = supportService.normalizeText(req.text);
        if (req.strategyName == null || req.strategyName.trim().isEmpty()) {
            req.strategyName = "all";
        }
        if (req.initialCapital == null || req.initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            req.initialCapital = BigDecimal.valueOf(10000);
        }
        if (req.tradeNotional == null || req.tradeNotional.compareTo(BigDecimal.ZERO) <= 0) {
            req.tradeNotional = req.initialCapital;
        }
        if (req.feeRatePct == null || req.feeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            req.feeRatePct = BigDecimal.ZERO;
        }
        if (req.fallbackStopLossPct == null || req.fallbackStopLossPct.compareTo(BigDecimal.ZERO) < 0) {
            req.fallbackStopLossPct = BigDecimal.ZERO;
        }
        if (req.fallbackTakeProfitPct == null || req.fallbackTakeProfitPct.compareTo(BigDecimal.ZERO) < 0) {
            req.fallbackTakeProfitPct = BigDecimal.ZERO;
        }
        if (req.maxHoldBars == null || req.maxHoldBars < 0) {
            req.maxHoldBars = 0;
        }
        if (req.ignoreSentimentGuard == null) {
            req.ignoreSentimentGuard = true;
        }
        return req;
    }

    public StrategyRunResult initResult(String strategyName, BacktestParam param) {
        StrategyRunResult result = new StrategyRunResult();
        result.strategyName = strategyName;
        result.symbol = param.symbol;
        result.text = param.text;
        result.beginDate = param.beginDate;
        result.endDate = param.endDate;
        result.initialCapital = scale(param.initialCapital.doubleValue());
        result.tradeNotional = scale(param.tradeNotional.doubleValue());
        result.finalCapital = scale(param.initialCapital.doubleValue());
        result.feeRatePct = scale(param.feeRatePct.doubleValue());
        result.fallbackStopLossPct = scale(param.fallbackStopLossPct.doubleValue());
        result.fallbackTakeProfitPct = scale(param.fallbackTakeProfitPct.doubleValue());
        result.maxHoldBars = param.maxHoldBars;
        result.tradeList = new ArrayList<>();
        result.rejectReasonCounts = new LinkedHashMap<String, Integer>();
        return result;
    }

    public Bar toBar(TTbookOhlc ohlc, Duration duration) throws Exception {
        ZonedDateTime time = LocalDateTime.parse(ohlc.starttime, OHLC_TIME_FORMATTER)
                .atZone(ZoneId.systemDefault());
        return new BaseBar(duration, time, ohlc.open, ohlc.high, ohlc.low, ohlc.close, ohlc.volume);
    }

    public boolean addBar(BarSeries series, Bar newBar) {
        boolean replace = false;
        if (series.getBarCount() > 0) {
            ZonedDateTime lastBarTime = series.getLastBar().getEndTime();
            if (newBar.getEndTime().equals(lastBarTime)) {
                replace = true;
            } else if (newBar.getEndTime().isBefore(lastBarTime)) {
                return false;
            }
        }
        series.addBar(newBar, replace);
        return !replace;
    }

    public BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
