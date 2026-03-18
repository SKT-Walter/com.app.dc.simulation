package com.app.dc.service.dao;

import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.po.chatGPT.TTChatGPTAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ChatGPT MA20 分析历史查询服务（simulation 回测使用）。
 */
@Service
@Slf4j
public class ChatGPTAnalysisQueryService {

    @Autowired
    private ClickHouseDBUtils clickHouseDBUtils;

    @Value("${chatGPTAnalysisTable:chatgpt_analysis_ma20}")
    private String tableName;

    /**
     * 查询 MA20 分析历史。
     * beginTime/endTime 格式建议: yyyy-MM-dd HH:mm:ss
     */
    public List<TTChatGPTAnalysis> queryAnalysis(String symbol, String beginTime, String endTime, int limit) {
        if (StringUtils.isBlank(clickHouseDBUtils.getDbSourceName())) {
            return Collections.emptyList();
        }
        int safeLimit = limit <= 0 ? 200 : Math.min(limit, 5000);
        String table = safeTableName(tableName);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT analysis_time,symbol,analysis_type,image_logic,latest_stage,strategy,strategy_type,strategy_reason,strategy_ext,confidence,image_path,payload ")
                .append("FROM ").append(table).append(" WHERE 1=1");

        List<Object> args = new ArrayList<>();
        if (StringUtils.isNotBlank(symbol)) {
            sql.append(" AND symbol=?");
            args.add(symbol);
        }
        if (StringUtils.isNotBlank(beginTime)) {
            sql.append(" AND analysis_time>=toDateTime(?)");
            args.add(beginTime);
        }
        if (StringUtils.isNotBlank(endTime)) {
            sql.append(" AND analysis_time<=toDateTime(?)");
            args.add(endTime);
        }

        sql.append(" ORDER BY analysis_time DESC LIMIT ").append(safeLimit);
        log.info("ChatGPTAnalysisQueryService query sql:{}", sql);
        return clickHouseDBUtils.queryList(sql.toString(), args.toArray(), TTChatGPTAnalysis.class);
    }

    private String safeTableName(String input) {
        if (StringUtils.isBlank(input)) {
            return "chatgpt_analysis_ma20";
        }
        String trim = input.trim();
        if (!trim.matches("[A-Za-z0-9_.]+")) {
            return "chatgpt_analysis_ma20";
        }
        return trim;
    }
}
