package com.app.dc.service.simulation;

import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.po.TTbookOhlc;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class BacktestQueryService {

    @Autowired
    private ClickHouseDBUtils clickHouseDBUtils;

    public List<TTbookOhlc> queryOhlc(String symbol, String text, String beginDate, String endDate) {
        if (StringUtils.isBlank(clickHouseDBUtils.getDbSourceName())) {
            return Collections.emptyList();
        }

        QueryAndArgs qa = buildOhlcSql(symbol, text, beginDate, endDate);
        log.info("BacktestQueryService query sql:{}, args:{}", qa.sql, qa.args);
        return clickHouseDBUtils.queryList(qa.sql, qa.args.toArray(), TTbookOhlc.class);
    }

    public QueryAndArgs buildOhlcSql(String symbol, String text, String beginDate, String endDate) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ")
                .append("startTime AS starttime,")
                .append("endTime AS endtime,")
                .append("toDate(startTime) AS tradedate,")
                .append("fmtTime AS fmttime,")
                .append("securityID AS securityid,")
                .append("text,")
                .append("open,close,low,high,turnover,volume,inf1 ")
                .append("FROM dc.kline_view WHERE 1=1");

        List<Object> args = new ArrayList<>();
        if (StringUtils.isNotBlank(symbol)) {
            sql.append(" AND securityID=?");
            args.add(symbol.trim());
        }
        if (StringUtils.isNotBlank(text)) {
            sql.append(" AND lowerUTF8(text)=lowerUTF8(?)");
            args.add(normalizeText(text));
        }
        if (StringUtils.isNotBlank(beginDate)) {
            sql.append(" AND toDate(startTime)>=toDate(?)");
            args.add(beginDate.trim());
        }
        if (StringUtils.isNotBlank(endDate)) {
            sql.append(" AND toDate(startTime)<=toDate(?)");
            args.add(endDate.trim());
        }

        sql.append(" ORDER BY startTime ASC");
        return new QueryAndArgs(sql.toString(), args);
    }

    public String normalizeText(String text) {
        return StringUtils.isBlank(text) ? text : text.trim();
    }

    public static class QueryAndArgs {
        public final String sql;
        public final List<Object> args;

        public QueryAndArgs(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }
}
