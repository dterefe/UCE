package org.texttechnologylab.uce.common.models.search.promode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class ProQueryLexer {
    private static final Set<String> COMMAND_PREFIXES = Set.of(
            "K::", "P::", "C::", "O::", "F::", "G::", "S::",
            "LOC::", "R::",
            "Y::", "M::", "D::", "E::", "T::"
    );

    public List<ProQueryToken> tokenize(String input) {
        var tokens = new ArrayList<ProQueryToken>();
        if (input == null) input = "";
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;

            if (c == '(') {
                tokens.add(ProQueryToken.of(ProQueryTokenType.LPAREN, "(", start, ++i));
                continue;
            }
            if (c == ')') {
                tokens.add(ProQueryToken.of(ProQueryTokenType.RPAREN, ")", start, ++i));
                continue;
            }
            if (c == '&') {
                tokens.add(ProQueryToken.of(ProQueryTokenType.AND, "&", start, ++i));
                continue;
            }
            if (c == '|') {
                tokens.add(ProQueryToken.of(ProQueryTokenType.OR, "|", start, ++i));
                continue;
            }
            if (c == '!') {
                if (startsWith(input, i, "!=") && isBareOperator(input, i, 2)) {
                    tokens.add(ProQueryToken.of(ProQueryTokenType.NOT_EQUAL, "!=", start, i + 2));
                    i += 2;
                    continue;
                }
                if (startsWith(input, i, "!=")) {
                    throw new ProModeSyntaxException("Invalid comparison operator at position " + i + ". Use spaces around !=.");
                }
                tokens.add(ProQueryToken.of(ProQueryTokenType.NOT, "!", start, ++i));
                continue;
            }
            if (c == '=' && isBareOperator(input, i, 1)) {
                tokens.add(ProQueryToken.of(ProQueryTokenType.EQUAL, "=", start, ++i));
                continue;
            }
            if (c == '=') {
                throw new ProModeSyntaxException("Invalid comparison operator at position " + i + ". Use spaces around =.");
            }
            if (startsWith(input, i, "<->")) {
                tokens.add(ProQueryToken.followedBy("<->", 1, start, i + 3));
                i += 3;
                continue;
            }
            if (startsWith(input, i, ">=") && isBareOperator(input, i, 2)) {
                tokens.add(ProQueryToken.of(ProQueryTokenType.GREATER_THAN_OR_EQUAL, ">=", start, i + 2));
                i += 2;
                continue;
            }
            if (c == '>' && isBareOperator(input, i, 1)) {
                tokens.add(ProQueryToken.of(ProQueryTokenType.GREATER_THAN, ">", start, ++i));
                continue;
            }
            if (c == '>') {
                throw new ProModeSyntaxException("Invalid comparison operator at position " + i + ". Use spaces around >.");
            }
            if (c == '<') {
                if (startsWith(input, i, "<=") && isBareOperator(input, i, 2)) {
                    tokens.add(ProQueryToken.of(ProQueryTokenType.LESS_THAN_OR_EQUAL, "<=", start, i + 2));
                    i += 2;
                    continue;
                }
                int end = input.indexOf('>', i + 1);
                if (end > i + 1) {
                    String between = input.substring(i + 1, end).trim();
                    if (between.matches("\\d+")) {
                        int distance = Integer.parseInt(between);
                        if (distance <= 0) {
                            throw new ProModeSyntaxException("Invalid <N> distance at position " + i + ": must be > 0");
                        }
                        tokens.add(ProQueryToken.followedBy("<" + between + ">", distance, start, end + 1));
                        i = end + 1;
                        continue;
                    }
                }
                if (isBareOperator(input, i, 1)) {
                    tokens.add(ProQueryToken.of(ProQueryTokenType.LESS_THAN, "<", start, ++i));
                    continue;
                }
                throw new ProModeSyntaxException("Invalid followed-by operator at position " + i + ". Use <-> or <N>.");
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < input.length()) {
                    char qc = input.charAt(i);
                    if (qc == '\\' && i + 1 < input.length()) {
                        sb.append(input.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (qc == quote) {
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(qc);
                    i++;
                }
                if (!closed) {
                    throw new ProModeSyntaxException("Unclosed quote starting at position " + start);
                }
                tokens.add(ProQueryToken.of(ProQueryTokenType.QUOTED, sb.toString(), start, i));
                continue;
            }

            String commandPrefix = commandPrefixAt(input, i);
            if (commandPrefix != null && i + commandPrefix.length() < input.length()) {
                int valueStart = i + commandPrefix.length();
                char vc = input.charAt(valueStart);
                if (vc == '\'' || vc == '"') {
                    char quote = vc;
                    i = valueStart + 1;
                    StringBuilder sb = new StringBuilder(commandPrefix);
                    boolean closed = false;
                    while (i < input.length()) {
                        char qc = input.charAt(i);
                        if (qc == '\\' && i + 1 < input.length()) {
                            sb.append(input.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        if (qc == quote) {
                            closed = true;
                            i++;
                            break;
                        }
                        sb.append(qc);
                        i++;
                    }
                    if (!closed) {
                        throw new ProModeSyntaxException("Unclosed quote starting at position " + valueStart);
                    }
                    tokens.add(ProQueryToken.of(ProQueryTokenType.TERM, sb.toString(), start, i));
                    continue;
                }
            }

            while (i < input.length()) {
                char cc = input.charAt(i);
                if (Character.isWhitespace(cc) || cc == '(' || cc == ')' || cc == '&' || cc == '|' || cc == '!') break;
                if (cc == '<' || cc == '>' || cc == '=') break;
                i++;
            }
            String text = input.substring(start, i);
            if (!text.isBlank()) tokens.add(ProQueryToken.of(ProQueryTokenType.TERM, text, start, i));
        }

        tokens.add(ProQueryToken.of(ProQueryTokenType.EOF, "", input.length(), input.length()));
        return tokens;
    }

    private boolean startsWith(String s, int idx, String prefix) {
        return s.regionMatches(idx, prefix, 0, prefix.length());
    }

    private String commandPrefixAt(String s, int idx) {
        return COMMAND_PREFIXES.stream()
                .filter(prefix -> startsWith(s, idx, prefix))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private boolean isBareOperator(String input, int idx, int length) {
        boolean leftBoundary = idx == 0 || Character.isWhitespace(input.charAt(idx - 1));
        int rightIndex = idx + length;
        boolean rightBoundary = rightIndex >= input.length() || Character.isWhitespace(input.charAt(rightIndex));
        return leftBoundary && rightBoundary;
    }
}
