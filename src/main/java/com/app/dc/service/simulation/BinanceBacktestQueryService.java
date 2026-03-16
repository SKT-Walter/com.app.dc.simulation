package com.app.dc.service.simulation;

import com.app.common.condition.BuildConditionType;
import com.app.common.condition.Condition;
import com.app.common.condition.ConditionInfo;
import com.app.common.condition.ConditionOperator;
import com.app.common.condition.OrderInfo;
import com.app.common.db.ClickHouseDBUtils;
import com.app.dc.po.TTbookOhlc;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.util.TextUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 回测历史 K 线查询服务。
 */
@Service
@Slf4j
public class BinanceBacktestQueryService {

    @Autowired
    private ClickHouseDBUtils clickHouseDBUtils;

    /**
     * 查询指定交易对、周期、时间区间的历史 K 线。
     */
    public List<TTbookOhlc> queryOhlc(String symbol, String text, String beginDate, String endDate) throws Exception {
        String sql = buildOhlcSql(symbol, text, beginDate, endDate);
        log.info("BinanceBacktestQueryService query sql:{}", sql);
        if (StringUtils.isBlank(clickHouseDBUtils.getDbSourceName())) {
            return Collections.emptyList();
        }
        return clickHouseDBUtils.queryList(sql, new Object[]{}, TTbookOhlc.class);
    }

    /**
     * 构建 ClickHouse 查询 SQL。
     */
    public String buildOhlcSql(String symbol, String text, String beginDate, String endDate) throws Exception {
        Condition condition = new Condition();
        condition.setTableName("kline final");

        if (!TextUtils.isEmpty(symbol)) {
            condition.AndCondition(new ConditionInfo("securityID", symbol, ConditionOperator.EqualTo, true));
        }
        if (!TextUtils.isEmpty(text)) {
            condition.AndCondition(new ConditionInfo("text", normalizeText(text), ConditionOperator.EqualTo, true));
        }
        if (!TextUtils.isEmpty(beginDate) && !TextUtils.isEmpty(endDate)) {
            condition.AndCondition(new ConditionInfo("tradeDate", beginDate, ConditionOperator.GreaterThanOrEqualTo, true));
            condition.AndCondition(new ConditionInfo("tradeDate", endDate, ConditionOperator.LessThanOrEqualTo, true));
        }

        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setFieldName("closeTime");
        orderInfo.setOrderType(com.app.common.condition.OrderType.Asc);
        condition.getOrderInfos().add(orderInfo);
        return (String) condition.BuildCondition(BuildConditionType.MySql);
    }

    /**
     * 统一周期字符串。
     */
    public String normalizeText(String text) {
        return StringUtils.isBlank(text) ? text : text.trim().toUpperCase();
    }
}
