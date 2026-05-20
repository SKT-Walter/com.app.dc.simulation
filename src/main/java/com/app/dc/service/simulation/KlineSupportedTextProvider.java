package com.app.dc.service.simulation;

import com.app.dc.utils.INDSvrClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class KlineSupportedTextProvider {

    private static final List<String> DEFAULT_TEXTS = buildDefaultTexts();
    private static final String DEFAULT_TEXT = "15m";

    @Autowired(required = false)
    private INDSvrClient indSvrClient;

    @Value("${indServerKey:SERVER.INDSvr}")
    private String indServerKey;

    @Value("${kline.supported.texts.fallback:1m,5m,15m,30m,1h,1d}")
    private String fallbackTexts;

    @Value("${kline.default.text.fallback:15m}")
    private String fallbackDefaultText;

    @Value("${kline.supported.texts.cacheMs:300000}")
    private long cacheMs;

    private volatile List<String> cachedTexts;
    private volatile String cachedDefaultText;
    private volatile long cacheExpireAtMs;
    private volatile boolean clientInitialized;

    @PostConstruct
    public void init() {
        applyFallback();
        initClientIfNeeded();
    }

    public synchronized List<String> getSupportedTexts() {
        refreshIfNeeded();
        return new ArrayList<String>(cachedTexts == null ? DEFAULT_TEXTS : cachedTexts);
    }

    public synchronized String getDefaultText() {
        refreshIfNeeded();
        return StringUtils.defaultIfBlank(cachedDefaultText, DEFAULT_TEXT);
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (cachedTexts != null && now < cacheExpireAtMs) {
            return;
        }
        if (!loadFromInd()) {
            applyFallback();
        }
        cacheExpireAtMs = now + Math.max(1000L, cacheMs);
    }

    private boolean loadFromInd() {
        initClientIfNeeded();
        if (indSvrClient == null) {
            return false;
        }
        try {
            LinkedHashMap<String, Object> result = indSvrClient.request(
                    "dc.ind.kline.supported.texts.query",
                    new LinkedHashMap<String, Object>());
            if (result == null || result.isEmpty()) {
                return false;
            }
            if (parseCode(result.get("code")) != 0 && !(result.get("data") instanceof Map)) {
                return false;
            }
            Object dataValue = result.get("data");
            Map<String, Object> data = dataValue instanceof Map
                    ? (Map<String, Object>) dataValue
                    : Collections.<String, Object>emptyMap();
            List<String> texts = normalizeTexts(data.get("supportedTexts"));
            if (texts.isEmpty()) {
                return false;
            }
            String defaultText = normalizeText(data.get("defaultText"));
            if (!texts.contains(defaultText)) {
                defaultText = texts.contains(DEFAULT_TEXT) ? DEFAULT_TEXT : texts.get(0);
            }
            cachedTexts = texts;
            cachedDefaultText = defaultText;
            return true;
        } catch (Exception e) {
            log.warn("KlineSupportedTextProvider loadFromInd failed, reason:{}", e.getMessage());
            return false;
        }
    }

    private void initClientIfNeeded() {
        if (clientInitialized || indSvrClient == null) {
            return;
        }
        synchronized (this) {
            if (clientInitialized || indSvrClient == null) {
                return;
            }
            if (StringUtils.isNotBlank(indServerKey)) {
                indSvrClient.serverName = indServerKey.trim();
            }
            indSvrClient.init();
            clientInitialized = true;
        }
    }

    private void applyFallback() {
        List<String> texts = normalizeTexts(fallbackTexts);
        if (texts.isEmpty()) {
            texts = new ArrayList<String>(DEFAULT_TEXTS);
        }
        String defaultText = normalizeText(fallbackDefaultText);
        if (!texts.contains(defaultText)) {
            defaultText = texts.contains(DEFAULT_TEXT) ? DEFAULT_TEXT : texts.get(0);
        }
        cachedTexts = texts;
        cachedDefaultText = defaultText;
    }

    private List<String> normalizeTexts(Object value) {
        LinkedHashSet<String> items = new LinkedHashSet<String>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                String normalized = normalizeText(item);
                if (StringUtils.isNotBlank(normalized)) {
                    items.add(normalized);
                }
            }
        } else {
            String raw = String.valueOf(value == null ? "" : value);
            if (StringUtils.isNotBlank(raw)) {
                String[] parts = raw.split("[,|\\s]+");
                for (String part : parts) {
                    String normalized = normalizeText(part);
                    if (StringUtils.isNotBlank(normalized)) {
                        items.add(normalized);
                    }
                }
            }
        }
        return new ArrayList<String>(items);
    }

    private String normalizeText(Object value) {
        return StringUtils.defaultString(value == null ? "" : String.valueOf(value)).trim().toLowerCase(Locale.ROOT);
    }

    private int parseCode(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static List<String> buildDefaultTexts() {
        List<String> texts = new ArrayList<String>();
        texts.add("1m");
        texts.add("5m");
        texts.add("15m");
        texts.add("30m");
        texts.add("1h");
        texts.add("1d");
        return texts;
    }
}
