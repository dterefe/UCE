package org.texttechnologylab.uce.common.models.search.promode;

public enum ProQueryTokenType {
    TERM,
    QUOTED,
    AND,
    OR,
    NOT,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    EQUAL,
    NOT_EQUAL,
    LPAREN,
    RPAREN,
    FOLLOWED_BY,
    EOF
}
