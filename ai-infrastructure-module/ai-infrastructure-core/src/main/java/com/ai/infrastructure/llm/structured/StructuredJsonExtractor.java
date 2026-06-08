package com.ai.infrastructure.llm.structured;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Provider-agnostic extraction of a JSON payload from an LLM response string.
 *
 * <p>Many providers sometimes wrap structured JSON in markdown fences or brief prose. This utility strips the most
 * common wrappers and extracts the first balanced JSON object/array.</p>
 */
@Slf4j
@Component
public class StructuredJsonExtractor {

    public StructuredJsonExtraction extractFirstJson(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return StructuredJsonExtraction.empty();
        }

        String sanitized = stripCodeFences(rawContent);
        int start = findFirstJsonStart(sanitized);
        if (start < 0) {
            return StructuredJsonExtraction.empty();
        }

        char startChar = sanitized.charAt(start);
        char expectedClose = matchingClose(startChar);
        if (expectedClose == 0) {
            return StructuredJsonExtraction.empty();
        }

        Deque<Character> stack = new ArrayDeque<>();
        stack.push(expectedClose);

        boolean inString = false;
        boolean escaped = false;
        for (int i = start + 1; i < sanitized.length(); i++) {
            char c = sanitized.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                stack.push('}');
                continue;
            }
            if (c == '[') {
                stack.push(']');
                continue;
            }

            if (c == '}' || c == ']') {
                if (stack.isEmpty()) {
                    return new StructuredJsonExtraction(sanitized.substring(start, i + 1), true, false);
                }
                char expected = stack.peek();
                if (expected != c) {
                    log.debug("Structured JSON extraction encountered mismatched close token expected={} actual={}", expected, c);
                    return new StructuredJsonExtraction(sanitized.substring(start), true, true);
                }
                stack.pop();
                if (stack.isEmpty()) {
                    return new StructuredJsonExtraction(sanitized.substring(start, i + 1), true, false);
                }
            }
        }

        // Ran out of content without balancing the JSON envelope -> likely truncated.
        return new StructuredJsonExtraction(sanitized.substring(start), true, true);
    }

    public String stripCodeFences(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }

        String trimmed = content.trim();

        // Some providers prepend markdown headings.
        while (trimmed.startsWith("###")) {
            int nextNewline = trimmed.indexOf('\n');
            if (nextNewline < 0) {
                break;
            }
            trimmed = trimmed.substring(nextNewline + 1).trim();
        }

        // Strip triple-backtick fence prefix.
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }

        // If there are nested fences, try to take the innermost fenced payload.
        int firstFence = trimmed.indexOf("```");
        if (firstFence >= 0) {
            int endFence = trimmed.indexOf("```", firstFence + 3);
            if (endFence > firstFence) {
                trimmed = trimmed.substring(firstFence + 3, endFence);
            }
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }

        return trimmed.trim();
    }

    private int findFirstJsonStart(String text) {
        if (!StringUtils.hasText(text)) {
            return -1;
        }
        int obj = text.indexOf('{');
        int arr = text.indexOf('[');
        if (obj < 0) {
            return arr;
        }
        if (arr < 0) {
            return obj;
        }
        return Math.min(obj, arr);
    }

    private char matchingClose(char open) {
        return switch (open) {
            case '{' -> '}';
            case '[' -> ']';
            default -> 0;
        };
    }
}

