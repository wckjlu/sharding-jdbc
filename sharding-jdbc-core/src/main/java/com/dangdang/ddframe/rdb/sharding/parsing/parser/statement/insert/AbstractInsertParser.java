/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.parsing.parser.statement.insert;

import com.dangdang.ddframe.rdb.sharding.api.rule.ShardingRule;
import com.dangdang.ddframe.rdb.sharding.exception.ShardingJdbcException;
import com.dangdang.ddframe.rdb.sharding.parsing.lexer.token.Assist;
import com.dangdang.ddframe.rdb.sharding.parsing.lexer.token.DefaultKeyword;
import com.dangdang.ddframe.rdb.sharding.parsing.lexer.token.Symbol;
import com.dangdang.ddframe.rdb.sharding.parsing.lexer.token.TokenType;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.SQLParser;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.GeneratedKey;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.condition.Column;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.condition.Condition;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.exception.SQLParsingUnsupportedException;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expression.SQLExpression;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expression.SQLNumberExpression;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expression.SQLPlaceholderExpression;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.statement.SQLStatementParser;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.token.GeneratedKeyToken;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.token.ItemsToken;
import com.dangdang.ddframe.rdb.sharding.util.SQLUtil;
import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Insert语句解析器.
 *
 * @author zhangliang
 */
@Getter(AccessLevel.PROTECTED)
public abstract class AbstractInsertParser implements SQLStatementParser {
    
    private final SQLParser sqlParser;
    
    private final ShardingRule shardingRule;
    
    private final InsertStatement insertStatement;
    /**
     * 自动生成键是第几个插入字段
     * index 从 0 开始
     */
    @Getter(AccessLevel.NONE)
    private int generateKeyColumnIndex = -1;
    
    public AbstractInsertParser(final ShardingRule shardingRule, final SQLParser sqlParser) {
        this.sqlParser = sqlParser;
        this.shardingRule = shardingRule;
        insertStatement = new InsertStatement();
    }


// https://dev.mysql.com/doc/refman/5.7/en/insert.html

// 第一种
//    INSERT [LOW_PRIORITY | DELAYED | HIGH_PRIORITY] [IGNORE]
//            [INTO] tbl_name
//    [PARTITION (partition_name,...)]
//            [(col_name,...)]
//    {VALUES | VALUE} ({expr | DEFAULT},...),(...),...
//            [ ON DUPLICATE KEY UPDATE
//    col_name=expr
//        [, col_name=expr] ... ]

// 第二种
//    INSERT [LOW_PRIORITY | DELAYED | HIGH_PRIORITY] [IGNORE]
//            [INTO] tbl_name
//    [PARTITION (partition_name,...)]
//    SET col_name={expr | DEFAULT}, ...
//            [ ON DUPLICATE KEY UPDATE
//    col_name=expr
//        [, col_name=expr] ... ]

// 第三种
//    INSERT [LOW_PRIORITY | HIGH_PRIORITY] [IGNORE]
//            [INTO] tbl_name
//    [PARTITION (partition_name,...)]
//            [(col_name,...)]
//    SELECT ...
//            [ ON DUPLICATE KEY UPDATE
//    col_name=expr
//        [, col_name=expr] ... ]

    @Override
    public final InsertStatement parse() {
        sqlParser.getLexer().nextToken(); // 跳过 INSERT 关键字
        parseInto(); // 解析表
        parseColumns(); // 解析字段
        if (sqlParser.equalAny(DefaultKeyword.SELECT, Symbol.LEFT_PAREN)) {
            throw new UnsupportedOperationException("Cannot support subquery");
        }
        if (getValuesKeywords().contains(sqlParser.getLexer().getCurrentToken().getType())) { // 第一种插入SQL情况
            parseValues();
        } else if (getCustomizedInsertKeywords().contains(sqlParser.getLexer().getCurrentToken().getType())) { // 第二种插入SQL情况
            parseCustomizedInsert();
        }
        appendGenerateKey(); // 自增主键
        return insertStatement;
    }

    /**
     * 解析表
     */
    private void parseInto() {
        // 例如，Oracle，INSERT FIRST/ALL 目前不支持
        if (getUnsupportedKeywords().contains(sqlParser.getLexer().getCurrentToken().getType())) {
            throw new SQLParsingUnsupportedException(sqlParser.getLexer().getCurrentToken().getType());
        }
        sqlParser.skipUntil(DefaultKeyword.INTO);
        sqlParser.getLexer().nextToken();
        // 解析表
        sqlParser.parseSingleTable(insertStatement);
        skipBetweenTableAndValues();
    }
    
    protected Set<TokenType> getUnsupportedKeywords() {
        return Collections.emptySet();
    }

    /**
     * 跳过 表 和 插入字段 中间的 Token
     * 例如 MySQL ：[PARTITION (partition_name,...)]
     */
    private void skipBetweenTableAndValues() {
        while (getSkippedKeywordsBetweenTableAndValues().contains(sqlParser.getLexer().getCurrentToken().getType())) {
            sqlParser.getLexer().nextToken();
            if (sqlParser.equalAny(Symbol.LEFT_PAREN)) {
                sqlParser.skipParentheses();
            }
        }
    }
    
    protected Set<TokenType> getSkippedKeywordsBetweenTableAndValues() {
        return Collections.emptySet();
    }

    /**
     * 解析插入字段
     */
    private void parseColumns() {
        Collection<Column> result = new LinkedList<>();
        if (sqlParser.equalAny(Symbol.LEFT_PAREN)) {
            String tableName = insertStatement.getTables().getSingleTableName();
            Optional<String> generateKeyColumn = shardingRule.getGenerateKeyColumn(tableName); // 自动生成键信息
            int count = 0;
            do {
                // Column 插入字段
                sqlParser.getLexer().nextToken();
                String columnName = SQLUtil.getExactlyValue(sqlParser.getLexer().getCurrentToken().getLiterals());
                result.add(new Column(columnName, tableName));
                sqlParser.getLexer().nextToken();
                // 自动生成键
                if (generateKeyColumn.isPresent() && generateKeyColumn.get().equalsIgnoreCase(columnName)) {
                    generateKeyColumnIndex = count;
                }
                count++;
            } while (!sqlParser.equalAny(Symbol.RIGHT_PAREN) && !sqlParser.equalAny(Assist.END));
            //
            insertStatement.setColumnsListLastPosition(sqlParser.getLexer().getCurrentToken().getEndPosition() - sqlParser.getLexer().getCurrentToken().getLiterals().length());
            //
            sqlParser.getLexer().nextToken();
        }
        insertStatement.getColumns().addAll(result);
    }
    
    protected Set<TokenType> getValuesKeywords() {
        return Sets.<TokenType>newHashSet(DefaultKeyword.VALUES);
    }

    /**
     * 解析值字段
     */
    private void parseValues() {
        boolean parsed = false;
        do {
            if (parsed) { // 只允许INSERT INTO 一条
                throw new UnsupportedOperationException("Cannot support multiple insert");
            }
            sqlParser.getLexer().nextToken();
            sqlParser.accept(Symbol.LEFT_PAREN);
            // 解析表达式
            List<SQLExpression> sqlExpressions = new LinkedList<>();
            do {
                sqlExpressions.add(sqlParser.parseExpression());
            } while (sqlParser.skipIfEqual(Symbol.COMMA));
            //
            insertStatement.setValuesListLastPosition(sqlParser.getLexer().getCurrentToken().getEndPosition() - sqlParser.getLexer().getCurrentToken().getLiterals().length());
            // 解析值字段
            int count = 0;
            for (Column each : insertStatement.getColumns()) {
                SQLExpression sqlExpression = sqlExpressions.get(count);
                insertStatement.getConditions().add(new Condition(each, sqlExpression), shardingRule);
                if (generateKeyColumnIndex == count) { // 自动生成键
                    insertStatement.setGeneratedKey(createGeneratedKey(each, sqlExpression));
                }
                count++;
            }
            sqlParser.accept(Symbol.RIGHT_PAREN);
            parsed = true;
        }
        while (sqlParser.equalAny(Symbol.COMMA)); // 字段以 "," 分隔
    }

    /**
     * 创建 自动生成键
     *
     * @param column 字段
     * @param sqlExpression 表达式
     * @return 自动生成键
     */
    private GeneratedKey createGeneratedKey(final Column column, final SQLExpression sqlExpression) {
        GeneratedKey result;
        if (sqlExpression instanceof SQLPlaceholderExpression) { // 占位符
            result = new GeneratedKey(column.getName(), ((SQLPlaceholderExpression) sqlExpression).getIndex(), null);
        } else if (sqlExpression instanceof SQLNumberExpression) { // 数字
            result = new GeneratedKey(column.getName(), -1, ((SQLNumberExpression) sqlExpression).getNumber());
        } else {
            throw new ShardingJdbcException("Generated key only support number.");
        }
        return result;
    }
    
    protected Set<TokenType> getCustomizedInsertKeywords() {
        return Collections.emptySet();
    }
    
    protected void parseCustomizedInsert() {
    }

    /**
     * 当表设置自动生成键，并且插入SQL没写自增字段，增加该字段
     */
    private void appendGenerateKey() {
        // 当表设置自动生成键，并且插入SQL没写自增字段
        String tableName = insertStatement.getTables().getSingleTableName();
        Optional<String> generateKeyColumn = shardingRule.getGenerateKeyColumn(tableName);
        if (!generateKeyColumn.isPresent() || null != insertStatement.getGeneratedKey()) {
            return;
        }
        // ItemsToken
        ItemsToken columnsToken = new ItemsToken(insertStatement.getColumnsListLastPosition());
        columnsToken.getItems().add(generateKeyColumn.get());
        insertStatement.getSqlTokens().add(columnsToken);
        // GeneratedKeyToken
        insertStatement.getSqlTokens().add(new GeneratedKeyToken(insertStatement.getValuesListLastPosition()));
    }
}
