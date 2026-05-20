package com.app.dc.service.simulation;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class KlineSupportedTextProvider {

    private static final List<String> DEFAULT_TEXTS = buildDefaultTexts();
    private static final String DEFAULT_TEXT = "15m";

    @Value("${kline.supported.texts:1m,5m,15m,30m,1h,1d}")
    private String configuredSupportedTexts;

    @Value("${kline.default.text:15m}")
    private String configuredDefaultText;

    public List<String> getSupportedTexts() {
        List<String> texts = normalizeTexts(configuredSupportedTexts);
        return texts.isEmpty() ? new ArrayList<String>(DEFAULT_TEXTS) : texts;
    }

    public String getDefaultText() {
        List<String> texts = getSupportedTexts();
        String normalized = normalizeText(configuredDefaultText);
        if (texts.contains(normalized)) {
            return normalized;
        }
        if (texts.contains(DEFAULT_TEXT)) {
            return DEFAULT_TEXT;
        }
        return texts.isEmpty() ? DEFAULT_TEXT : texts.get(0);
    }

    private List<String> normalizeTexts(String raw) {
        LinkedHashSet<String> items = new LinkedHashSet<String>();
        if (StringUtils.isNotBlank(raw)) {
            String[] parts = raw.split("[,|\\s]+");
            for (String part : parts) {
                String normalized = normalizeText(part);
                if (StringUtils.isNotBlank(normalized)) {
                    items.add(normalized);
                }
            }
        }
        return new ArrayList<String>(items);
    }

    private String normalizeText(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
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
