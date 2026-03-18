package com.app.dc.service.dao;

import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.po.sentiment.TTChatGPTSentiment;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ChatGPT 鑸嗘儏鍘嗗彶鏌ヨ鏈嶅姟锛坰imulation 鍥炴祴浣跨敤锛夈€? */
@Service
@Slf4j
public class ChatGPTSentimentQueryService {

    @Autowired
    private ClickHouseDBUtils clickHouseDBUtils;

    @Value("${chatGPTSentimentTable:chatgpt_sentiment}")
    private String tableName;

    /**
     * 鏌ヨ鑸嗘儏鍒嗘瀽鍘嗗彶璁板綍銆?     * beginTime/endTime 鏍煎紡寤鸿: yyyy-MM-dd HH:mm:ss
     */
    public List<TTChatGPTSentiment> querySentiment(String symbol, String beginTime, String endTime, int limit) {
        if (StringUtils.isBlank(clickHouseDBUtils.getDbSourceName())) {
            return Collections.emptyList();
        }
        int safeLimit = limit <= 0 ? 200 : Math.min(limit, 5000);
        String table = safeTableName(tableName);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT analysis_time,symbol,analysis_type,sentiment,risk_level,risk_action,event_focus,summary,confidence,source_urls,payload ")
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
        log.info("ChatGPTSentimentQueryService query sql:{}", sql);

        return clickHouseDBUtils.queryList(sql.toString(), args.toArray(), TTChatGPTSentiment.class);
    }

    /**
     * 闄愬埗琛ㄥ悕浠呭寘鍚畨鍏ㄥ瓧绗︺€?     */
    private String safeTableName(String input) {
        if (StringUtils.isBlank(input)) {
            return "chatgpt_sentiment";
        }
        String trim = input.trim();
        if (!trim.matches("[A-Za-z0-9_.]+")) {
            return "chatgpt_sentiment";
        }
        return trim;
    }
}
