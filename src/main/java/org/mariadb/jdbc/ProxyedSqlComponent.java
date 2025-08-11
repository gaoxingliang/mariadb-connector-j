package org.mariadb.jdbc;

import java.lang.reflect.*;
import java.sql.*;

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
            new Class<?>[] {Statement.class},
            new ProxyedStatementHandler(stmt));
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
      if ("executeQuery".equals(methodName)) {
        // 重写 executeQuery 方法，返回最后一个ResultSet
        return handleMultiQuery((String) args[0]);
      } else if ("close".equals(methodName)) {
        // 正确关闭
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

    private Object handleMultiQuery(String sql) throws SQLException {
      // 执行多条SQL
      boolean hasResultSet = originalStatement.execute(sql);
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

    public static Statement newProxyInstance(Connection connection) throws SQLException {
      Statement originalStatement = connection.createStatement();
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {Statement.class},
              new ProxyedStatementHandler(originalStatement));
    }
  }
}
