package com.xiaozhi.utils;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author shiyue
 * @version 1.0 2025/9/13 23:00
 */
public class KoreanNumberConverter {
    private static final String[] UNITS = {"", "십", "백", "천"};
    private static final String[] DIGITS = {"", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"};

    public static String toKorean(long number) {
        if (number == 0) return "영";

        StringBuilder sb = new StringBuilder();
        String numStr = String.valueOf(number);
        int len = numStr.length();

        for (int i = 0; i < len; i++) {
            int digit = numStr.charAt(i) - '0';
            int unitIndex = (len - 1 - i) % 4;
            int groupIndex = (len - 1 - i) / 4;

            if (digit != 0) {
                sb.append(DIGITS[digit]).append(UNITS[unitIndex]);
            }
            if (unitIndex == 0 && !sb.isEmpty()) {
                switch (groupIndex) {
                    case 1 -> sb.append("만");
                    case 2 -> sb.append("억");
                    case 3 -> sb.append("조");
                }
            }
        }
        return sb.toString();
    }

    @NotNull
    public static String convertNumberToKO(String partialText) {
        Pattern pattern = Pattern.compile("\\d+");
        Matcher matcher = pattern.matcher(partialText);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String numberStr = matcher.group();
            String koreanNumber = KoreanNumberConverter.toKorean(Long.parseLong(numberStr));
            matcher.appendReplacement(sb, koreanNumber);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
