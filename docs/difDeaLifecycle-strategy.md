# difDeaLifecycle 策略说明

## 1. 策略概览

`difDeaLifecycle` 是一个基于 `5M` K 线的 DIF/DEA 生命周期趋势策略。

核心思想不是“只要交叉就开仓”，而是把信号拆成三层：

1. 空仓时，先判断当前交叉值不值得参与
2. 持仓后，持续判断趋势是否结束或只是中途洗盘
3. 提前离场后，不直接放弃原方向，而是继续观察是否存在二次启动机会

当前策略只在 `5M` 周期运行，并且要求至少预热 `35` 根 K 线后才开始参与决策。

---

## 2. 生命周期状态

策略内部维护以下生命周期状态：

- `NEUTRAL`：空闲，无持仓，也不在观察
- `LONG_ACTIVE`：多头持仓中
- `SHORT_ACTIVE`：空头持仓中
- `PENDING_LONG_LAUNCH`：多头观察态，等待更干净的再次启动
- `PENDING_SHORT_LAUNCH`：空头观察态，等待更干净的再次启动

---

## 3. 原始交叉定义

### 3.1 多头进入候选

满足以下条件时，产生金叉开多候选：

```text
prevDIF < prevDEA
currentDIF >= currentDEA
```

### 3.2 空头进入候选

满足以下条件时，产生死叉开空候选：

```text
prevDIF > prevDEA
currentDIF <= currentDEA
```

---

## 4. 空仓直接开仓逻辑

空仓时，交叉出现后不会立刻开仓，而是先过一组过滤条件。
只要命中任意一个过滤项，就不直接开仓，而是进入对应观察态。

### 4.1 多头空仓过滤

金叉开多候选按以下逻辑过滤：

1. 交叉密度过滤
   - 最近交叉次数过多，视为震荡

2. 单根大波幅过滤
   - 当前 K 线波幅过大时不追单
   - 计算方式：

```text
barRangePct = (high - low) / open * 100
```

   - 普通空仓开仓阈值：`1.5%`

3. 突发拉升交叉过滤
   - 防止前一根大幅拉升后，硬拐出一个金叉

4. 最近 4 根净走势反向过滤
   - 如果最近 4 根整体仍偏空，不直接开多

5. MA 结构过滤
   - `MA5` 已经向上
   - 但 `MA10` 最近连续 3 根仍向下
   - 这类场景更像下跌中的反抽，而不是趋势真正翻多

6. 低 MACD 压缩过滤
   - 最近 5 根中，有至少 4 根满足：

```text
abs(MACD) < 0.5
```

   - 视为低动能压缩，不直接参与

7. DIF/DEA 粘合过滤
   - 最近 5 根中，有至少 4 根满足：

```text
abs(DIF - DEA) <= 品种阈值
```

8. 弱交叉过滤
   - 当前交叉过弱：

```text
abs(MACD) < 0.12
```

### 4.2 空头空仓过滤

死叉开空候选逻辑与多头对称：

1. 交叉密度过滤
2. 单根大波幅过滤
3. 突发下杀交叉过滤
4. 最近 4 根净走势反向过滤
5. MA 结构过滤
   - `MA5` 向下
   - `MA10` 最近连续 3 根仍向上
6. 低 MACD 压缩过滤
7. DIF/DEA 粘合过滤
8. 弱交叉过滤

### 4.3 空仓直接开仓成功

如果交叉成立，且以上过滤均未命中：

- 多头发出：`ENTER_LONG`
- 空头发出：`ENTER_SHORT`

---

## 5. 持仓离场逻辑

持仓后，策略优先看反向交叉，其次看趋势衰竭。

### 5.1 多头持仓离场

#### 反向死叉离场

满足以下条件时，触发多头反向离场候选：

```text
prevDIF > prevDEA
currentDIF <= currentDEA
```

若这次反向交叉仍处于 DIF/DEA 粘合状态，则先不离场；
否则发出：

- `LEAVE_LONG`

#### 多头策略离场

当以下条件同时成立时，认为多头趋势衰竭：

1. `MACD` 在弱化窗口内持续走弱
2. `close` 在弱化窗口内持续走弱
3. `low` 在弱化窗口内持续走弱
4. 当前 `close` 跌破前 3 根 K 线实体底部最低值

命中后发出：

- `LEAVE_LONG`
- 原因：`macd_shrinking_and_close_weakening`

### 5.2 空头持仓离场

#### 反向金叉离场

满足以下条件时，触发空头反向离场候选：

```text
prevDIF < prevDEA
currentDIF >= currentDEA
```

若不属于粘合里的假反向，则发出：

- `LEAVE_SHORT`

#### 空头策略离场

当以下条件同时成立时，认为空头趋势衰竭：

1. `MACD` 在恢复
2. `close` 在走强
3. `low` 在抬高
4. 当前 `close` 突破前 3 根 K 线实体顶部最高值

命中后发出：

- `LEAVE_SHORT`
- 原因：`macd_recovering_and_close_strengthening`

---

## 6. 策略离场后的观察态

当前策略认为，趋势中途经常会出现洗盘或反抽，因此策略离场后不会直接回空闲。

### 6.1 多头策略离场后

如果多头是因为：

- `macd_shrinking_and_close_weakening`

而离场，则状态推进为：

- `PENDING_LONG_LAUNCH`

### 6.2 空头策略离场后

如果空头是因为：

- `macd_recovering_and_close_strengthening`

而离场，则状态推进为：

- `PENDING_SHORT_LAUNCH`

也就是说：

- 多头先平仓，再继续观察是否二次启动
- 空头先平仓，再继续观察是否二次启动

---

## 7. Pending 补开仓逻辑

观察态下，策略不再走空仓那一整套过滤，而是专门看“启动质量是否足够”。

### 7.1 多头补开仓

`PENDING_LONG_LAUNCH` 下，需同时满足：

1. `currentDIF >= currentDEA`
2. `currentMACD > 0.18`
3. 当前 K 线是阳线：`close > open`
4. `abs(currentMACD) > abs(prevMACD)`
5. MACD 有继续增强确认
6. 价格有继续走强确认
7. `close` 突破观察期高点
   - 严格突破：

```text
currentClose > pendingHighClose
```

   - 或容差突破：

```text
currentClose >= pendingHighClose - 0.1
&& currentClose > currentOpen
```

8. 当前补开仓 K 线波幅不能过大
   - 阈值：`0.8%`

满足后发出：

- `ENTER_LONG`
- 原因：`launch_entry_after_macd_expand`

### 7.2 空头补开仓

`PENDING_SHORT_LAUNCH` 下，需同时满足：

1. `currentDIF <= currentDEA`
2. `currentMACD < -0.18`
3. 当前 K 线是阴线：`close < open`
4. `abs(currentMACD) > abs(prevMACD)`
5. MACD 有继续增强确认
6. 价格有继续走弱确认
7. `close` 跌破观察期低点
   - 严格突破：

```text
currentClose < pendingLowClose
```

   - 或容差突破：

```text
currentClose <= pendingLowClose + 0.1
&& currentClose < currentOpen
```

8. 当前补开仓 K 线波幅不能过大
   - 阈值：`0.8%`

满足后发出：

- `ENTER_SHORT`
- 原因：`launch_entry_after_macd_expand`

---

## 8. Pending 失效逻辑

当前 `pendingBars` 主要用于日志观察，不作为固定超时裁决。

观察态失效主要靠以下规则：

- 多头观察态下，如果 `DIF < DEA`，则失效
- 空头观察态下，如果 `DIF > DEA`，则失效
- 若中途出现新的反向交叉周期，则旧观察态失效，转入新周期判断

---

## 9. 当前策略风格总结

这版策略的风格可以概括为：

- 空仓时比较谨慎，重点防震荡、弱交叉、突发波动、反向净走势，以及“短反弹但高一级趋势未翻”的假启动
- 持仓时优先尊重反向交叉，策略离场则要求更明确的价格结构确认
- 离场后不轻易放弃原方向，而是继续观察趋势是否会出现二次启动

整体目标是：

- 少开在趋势尾部
- 少开在反抽高点或回踩低点
- 对真正延续的趋势，尽量通过 `pending` 机制重新参与
