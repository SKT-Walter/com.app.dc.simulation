package com.app.dc.service.simulation.dynamic;
import com.app.common.utils.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
@Service public class DynamicStrategyCatalog {
 @Value("${backtest.dynamic.catalogFile:./config/dynamic_strategy_catalog.json}")
 private String file;
 private List<DynamicStrategyMeta> strategies = Collections.emptyList();

 @PostConstruct
 public void load() {
  try {
   String json = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
   Catalog c = JsonUtils.Deserialize(json, Catalog.class);
   if (c == null || c.strategies == null || c.strategies.isEmpty()) throw new IllegalStateException("empty catalog");
   strategies = c.strategies;
  } catch (Exception e) {
   throw new IllegalStateException("cannot load dynamic strategy catalog: " + file, e);
  }
 }

 public List<DynamicStrategyMeta> all() {
  return Collections.unmodifiableList(strategies);
 }

 public DynamicStrategyMeta find(String name) {
  if (name == null) return null;
  for (DynamicStrategyMeta m : strategies) if (name.equalsIgnoreCase(m.strategyName)) return m;
  return null;
 }

 public List<DynamicStrategyMeta> candidates(BacktestRegime r) {
  List<DynamicStrategyMeta> out = new ArrayList<DynamicStrategyMeta>();
  for (DynamicStrategyMeta m : strategies) if (m.supports(r)) out.add(m);
  return out;
 }

 public static class Catalog {
  public List<DynamicStrategyMeta> strategies;
 }
}
