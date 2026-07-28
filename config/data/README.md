# 本地回测行情

本目录支持两种文件：

- 旧版单文件：`{SYMBOL}.json`，例如 `ETHUSDT.json`。
- 年度文件：`{SYMBOL}_{YEAR}.json`，例如 `ETHUSDT_2025.json`。

旧版单文件存在时优先使用；否则回测会按照 `begin/end` 自动加载涉及的全部年度文件。
年度文件缺少其中一年时会直接报错，不会把本地数据和 ClickHouse 混合。

可通过 `com.app.dc.binance.BinanceVisionKlineRunner` 从 Binance Vision 下载并生成年度文件，
详细用法见 `src/docs/binance_vision_kline.md`。

JSON 根节点为兼容 `MarketDataSnapshotFullRefresh[]` 的数组。行情文件和下载缓存不会提交到 Git。
