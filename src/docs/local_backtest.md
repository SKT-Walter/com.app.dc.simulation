# Simulator 本地回测

## 确定性动态链路

```text
已收线K线
 → Market Regime
 → 硬规则候选
 → 确定性评分
 → 稳定切换
 → 单一运行策略
 → 信号
 → 风控
 → 模拟成交
```

该链路不调用 AI，也不依赖评分文件。相同的行情、目录和配置应产生完全相同的路由与成交结果。

## Maven 运行

```bash
mvn compile exec:java \
  -Dexec.mainClass=com.app.dc.simulation.LocalBacktestRunner \
  -Dexec.args="--strategy=deterministic --symbols=ETHUSDT --text=15m --begin=2026-06-01 --end=2026-06-30 --chunkDays=2"
```

`--strategy=dynamic` 保留为确定性动态路由的兼容别名，报告统一标记为 `deterministic`。

## Java 运行

```bash
java -cp "target/classes;target/javalib/*" com.app.dc.simulation.LocalBacktestRunner --strategy=deterministic --symbols=ETHUSDT --text=15m --begin=2026-06-01 --end=2026-06-30 --chunkDays=2
```

## 数据来源

1. 优先读取 `config/data/{SYMBOL}.json`。
2. 本地文件不存在时查询 ClickHouse `dc.kline_view`。
3. 本地文件存在但损坏或区间无匹配数据时直接报错，不静默切换数据源。

## 常用参数

- `--strategy=all|deterministic|策略名`
- `--symbols=ETHUSDT,BTCUSDT`
- `--text=15m`
- `--begin=yyyy-MM-dd`
- `--end=yyyy-MM-dd`
- `--capital=10000`
- `--fee=0.04`
- `--stopLoss=6`
- `--takeProfit=7`
- `--tradeNotional=10000`：每笔使用固定名义本金，不随账户权益复利增长。
- `--maxHoldBars=0`
- `--chunkDays=2`
- `--ignoreSentimentGuard=true`

报告默认写入 `src/docs/backtest`，包含实际行情覆盖、Regime 分布、候选拒绝、确定性评分、策略切换、信号、风控拒绝和交易明细，不生成 compare 报告。
# 按品种评分档案

确定性回测会从 `config/strategy-profiles/{SYMBOL}.json` 加载品种评分档案。全局
`dynamic_strategy_catalog.json` 仍是默认目录；档案缺失或 `enabled=false` 时自动回退全局配置。

档案只允许覆盖策略启停、激活门槛、基础分数修正、BUY/SELL 方向修正和允许方向，
不会修改 Regime、策略算法、止损止盈或成交逻辑。报告中的“品种评分档案”、
“品种评分修正”和“按策略与方向收益归因”可用于核对实际生效情况。

当前档案状态：

- `BTCUSDT`：通过 2024 校准及 2025/2026 样本外验收，默认启用。
- `ETHUSDT`：样本外验收失败，保留配置但禁用。
- `SOLUSDT`：2024 校准失败，保留配置但禁用。
