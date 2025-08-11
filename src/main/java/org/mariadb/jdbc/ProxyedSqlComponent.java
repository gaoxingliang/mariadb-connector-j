package org.mariadb.jdbc;

import java.lang.reflect.*;
import java.sql.*;
import java.util.Arrays;

public class ProxyedSqlComponent {
    // 实现代理Connection，重写createStatement返回你的代理Statement
    public static class ConnectionInvocationHandler implements InvocationHandler {
        private final Connection originalConn;

        public ConnectionInvocationHandler(Connection conn) {
            this.originalConn = conn;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("createStatement".equals(method.getName())) {
                Statement stmt = (Statement) method.invoke(originalConn, args);
                return Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[]{Statement.class},
                        new ProxyedStatementHandler(stmt)
                );
            }
            return method.invoke(originalConn, args);
        }
    }

    public static class ProxyedStatementHandler implements InvocationHandler {
        private final Statement originalStatement;
        private ResultSet lastResultSet;

        public ProxyedStatementHandler(Statement originalStatement) {
            this.originalStatement = originalStatement;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if ("execute".equals(methodName)) {
                /**
                 * only change this method
                 * {@link Statement#execute(String)}
                 */
                String sql = (String) args[0];
                EXECUTE_METHOD methodEnum = null;
                if (args.length == 1) {
                    methodEnum = EXECUTE_METHOD.EXECUTE_ARG_SQL;
                } else if (args.length == 2) {
                    if (args[1] instanceof Integer) {
                        methodEnum = EXECUTE_METHOD.EXECUTE_ARG_SQL_INT;
                    } else if (args[1] instanceof int[]) {
                        methodEnum = EXECUTE_METHOD.EXECUTE_ARG_SQL_INT_ARRAY;
                    } else if (args[1] instanceof String[]) {
                        methodEnum = EXECUTE_METHOD.EXECUTE_ARG_SQL_STRING_ARRAY;
                    }
                } else {
                    throw new SQLException("Unknown sql method with args:" + Arrays.toString(args));
                }
                handleMultiQuery(sql, args, methodEnum);
                return lastResultSet != null;
            } else if ("executeQuery".equals(methodName)) {
                /**
                 * {@link Statement#executeQuery(String)}
                 */
                return handleMultiQuery((String) args[0], args, EXECUTE_METHOD.EXECUTE_QUERY_SQL);
            } else if ("close".equals(methodName)) {
                if (lastResultSet != null && !lastResultSet.isClosed()) {
                    lastResultSet.close();
                }
                return method.invoke(originalStatement, args);
            } else if ("getResultSet".equals(methodName)) {
                return this.lastResultSet;
            }

            // 其它方法直接委托
            return method.invoke(originalStatement, args);
        }

        private Object handleMultiQuery(String sql, Object[] args, EXECUTE_METHOD method) throws SQLException {
            // 执行多条SQL
            boolean hasResultSet;
            if (method == EXECUTE_METHOD.EXECUTE_ARG_SQL) {
                hasResultSet = originalStatement.execute(sql);
            } else if (method == EXECUTE_METHOD.EXECUTE_ARG_SQL_INT) {
                hasResultSet = originalStatement.execute(sql, (int) args[1]);
            } else if (method == EXECUTE_METHOD.EXECUTE_ARG_SQL_INT_ARRAY) {
                hasResultSet = originalStatement.execute(sql, (int[]) args[1]);
            } else if (method == EXECUTE_METHOD.EXECUTE_ARG_SQL_STRING_ARRAY) {
                hasResultSet = originalStatement.execute(sql, (String[]) args[1]);
            } else if (method == EXECUTE_METHOD.EXECUTE_QUERY_SQL) {
                originalStatement.execute(sql);
                hasResultSet = true;
            } else {
                throw new SQLException("Not implement sql method type - " + method);
            }
            ResultSet rs = null;
            ResultSet lastRs = null;
            int resultSetCount = 0;

            do {
                if (hasResultSet) {
                    rs = originalStatement.getResultSet();
                    resultSetCount++;
                    // 保存当前的ResultSet
                    lastRs = rs;
                } else {
                    // 不是ResultSet，可能是更新计数
                    int updateCount = originalStatement.getUpdateCount();
                }
                // 移动到下一个结果，但保持当前ResultSet不被关闭
                hasResultSet = originalStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT);
            } while (hasResultSet || originalStatement.getUpdateCount() != -1);

            this.lastResultSet = lastRs;
            return lastRs; // 返回最后一个ResultSet
        }

    }

    // method enum
    enum EXECUTE_METHOD {
        /**
         * {@link Statement#execute(String)}
         */
        EXECUTE_ARG_SQL,
        /**
         * {@link Statement#execute(String, int)}
         */
        EXECUTE_ARG_SQL_INT,
        /**
         * {@link Statement#execute(String, int[])}
         */
        EXECUTE_ARG_SQL_INT_ARRAY,
        /**
         * {@link Statement#execute(String, String[])}
         */
        EXECUTE_ARG_SQL_STRING_ARRAY,
        /**
         * {@link Statement#executeQuery(String)}
         */
        EXECUTE_QUERY_SQL

    }
}