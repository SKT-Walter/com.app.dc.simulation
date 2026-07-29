# Binance Vision 年度 K 线下载

## 数据链路

```text
Binance Vision U本位合约月包/日包
  → 下载 ZIP 与 .CHECKSUM
  → SHA-256 校验
  → 解析 CSV
  → 校验、排序、去重
  → config/data/{SYMBOL}_{YEAR}.json
  → LocalBacktestRunner
```

下载器不启动 Spring、ClickHouse、Gateway 或回测服务。ZIP 和校验文件缓存在
`config/data/.binance-cache`，正式 JSON 按北京时间交易日期划分年份。

## 下载一个年度

```bash
mvn compile exec:java \
  -Dexec.mainClass=com.app.dc.binance.BinanceVisionKlineRunner \
  -Dexec.args="--symbols=ETHUSDT --text=15m --year=2025"
```

Windows 编译后可直接运行：

```bat
java -cp "target/classes;target/javalib/*" com.app.dc.binance.BinanceVisionKlineRunner --symbols=ETHUSDT --text=15m --year=2025
```

多币种下载：

```bat
java -cp "target/classes;target/javalib/*" com.app.dc.binance.BinanceVisionKlineRunner --symbols=ETHUSDT,BTCUSDT,SOLUSDT --text=15m --year=2025
```

输出示例：

```text
config/data/ETHUSDT_2025.json
config/data/BTCUSDT_2025.json
config/data/SOLUSDT_2025.json
```

历史月份优先使用 monthly 包；monthly 不存在时自动查询 daily 包。当前年份只获取
Binance Vision 已发布的日期。正式年度文件默认不覆盖，更新当前年份时显式指定：

```bat
java -cp "target/classes;target/javalib/*" com.app.dc.binance.BinanceVisionKlineRunner --symbols=ETHUSDT --text=15m --year=2026 --overwrite=true
```

可选参数：

- `--projectDir=绝对路径`：IDE 工作目录不在 simulator 时指定工程目录。
- `--cacheDir=绝对路径`：修改 ZIP 缓存目录。
- `--overwrite=true`：在全部下载、解析和校验成功后原子替换年度 JSON。

## 离线回测

下载完成后按原入口运行：

```bat
java -cp "target/classes;target/javalib/*" com.app.dc.simulation.LocalBacktestRunner --strategy=deterministic --symbols=ETHUSDT --text=15m --begin=2025-01-01 --end=2025-12-31 --chunkDays=2
```

跨年度回测会自动合并对应文件：

```text
config/data/ETHUSDT_2024.json
config/data/ETHUSDT_2025.json
```

如果只存在其中一个年度文件，回测会明确提示缺失文件，不会静默查询 ClickHouse。

## 完整性规则

- ZIP 必须有对应 `.CHECKSUM` 且 SHA-256 一致。
- CSV 必须符合 Binance U 本位合约 K 线的 12 列格式。
- OHLC、成交量、时间戳、周期对齐和收线状态必须合法。
- 同一开盘时间出现不同数据时拒绝生成文件。
- 历史年份在上市后的时间断层或尾部缺失会失败；上市前的空月份允许跳过。
- 网络、校验或解析失败时不会替换已有年度文件。
