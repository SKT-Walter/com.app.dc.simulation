package com.app.dc.service.simulation.dynamic;
import com.app.common.utils.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.app.dc.service.simulation.strategy.SymbolStrategyNames;
@Service public class DynamicStrategyCatalog {
 @Value("${backtest.dynamic.catalogFile:./config/dynamic_strategy_catalog.json}")private String file;private List<DynamicStrategyMeta> strategies=Collections.emptyList();
 @PostConstruct public void load(){try{String json=read(file);Catalog c=JsonUtils.Deserialize(json,Catalog.class);if(c==null||c.strategies==null||c.strategies.isEmpty())throw new IllegalStateException("empty catalog");strategies=expand(c.strategies);}catch(Exception e){throw new IllegalStateException("cannot load dynamic strategy catalog: "+file,e);}}
 private String read(String location)throws Exception{if(location!=null&&location.startsWith("classpath:")){String name=location.substring("classpath:".length());while(name.startsWith("/"))name=name.substring(1);InputStream in=Thread.currentThread().getContextClassLoader().getResourceAsStream(name);if(in==null)throw new IllegalStateException("classpath resource not found: "+name);try{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>=0)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}finally{in.close();}}return new String(Files.readAllBytes(Paths.get(location)),StandardCharsets.UTF_8);}
 public List<DynamicStrategyMeta> all(){return Collections.unmodifiableList(strategies);}
 public DynamicStrategyMeta find(String name){if(name==null)return null;for(DynamicStrategyMeta m:strategies)if(name.equalsIgnoreCase(m.strategyName))return m;return null;}
 public List<DynamicStrategyMeta> candidates(BacktestRegime r){List<DynamicStrategyMeta> out=new ArrayList<DynamicStrategyMeta>();for(DynamicStrategyMeta m:strategies)if(m.supports(r))out.add(m);return out;}
 private List<DynamicStrategyMeta> expand(List<DynamicStrategyMeta> source){List<DynamicStrategyMeta> out=new ArrayList<DynamicStrategyMeta>();for(DynamicStrategyMeta m:source){out.add(m);boolean solOnly="solMomentumBullTrend".equalsIgnoreCase(m.strategyName)||"solBullLaunchTrend".equalsIgnoreCase(m.strategyName)||"solStructuralBearTrend".equalsIgnoreCase(m.strategyName);boolean ethOnly="ethStructuralBullTrend".equalsIgnoreCase(m.strategyName)||"ethStructuralBearTrend".equalsIgnoreCase(m.strategyName);boolean btcOnly="btcStructuralBullTrend".equalsIgnoreCase(m.strategyName)||"btcBullLaunchTrend".equalsIgnoreCase(m.strategyName)||"btcStructuralBearTrend".equalsIgnoreCase(m.strategyName);if(!solOnly&&!btcOnly)out.add(m.copyAs(SymbolStrategyNames.qualify(m.strategyName,"ETHUSDT"),"ETHUSDT"));if(!ethOnly&&!btcOnly)out.add(m.copyAs(SymbolStrategyNames.qualify(m.strategyName,"SOLUSDT"),"SOLUSDT"));if(!ethOnly&&!solOnly)out.add(m.copyAs(SymbolStrategyNames.qualify(m.strategyName,"BTCUSDT"),"BTCUSDT"));}return out;}
 public static class Catalog{public List<DynamicStrategyMeta> strategies;}
}
