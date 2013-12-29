package ru.regenix.jphp.tokenizer.token.expr;

import ru.regenix.jphp.tokenizer.TokenType;
import ru.regenix.jphp.tokenizer.token.Token;
import ru.regenix.jphp.tokenizer.TokenMeta;

abstract public class ExprToken extends Token {
    public ExprToken(TokenMeta meta, TokenType type) {
        super(meta, type);
    }

    public int getPriority(){
        return 0;
    }

    public Token getLast(){
        return this;
    }
}
