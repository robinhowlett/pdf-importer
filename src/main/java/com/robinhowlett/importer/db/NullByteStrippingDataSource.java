package com.robinhowlett.importer.db;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * DataSource wrapper that strips null bytes (0x00) from all string parameters before they reach
 * PostgreSQL. Some early-1990s Equibase PDFs produce null bytes in extracted text; PostgreSQL's
 * UTF-8 encoding rejects them with "invalid byte sequence for encoding UTF8: 0x00".
 */
public class NullByteStrippingDataSource implements DataSource {

    private final DataSource delegate;

    public NullByteStrippingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return wrap(delegate.getConnection(user, password));
    }

    private static Connection wrap(Connection conn) {
        return proxy(conn, Connection.class, (proxy, method, args) -> {
            Object result = method.invoke(conn, args);
            if (result instanceof PreparedStatement ps) {
                return proxy(ps, PreparedStatement.class, new StatementHandler(ps));
            }
            return result;
        });
    }

    private record StatementHandler(PreparedStatement ps) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("setString".equals(method.getName())
                    && args != null && args.length == 2 && args[1] instanceof String s) {
                args[1] = s.replace("\u0000", "");
            }
            return method.invoke(ps, args);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                new Class[]{iface},
                handler);
    }

    // --- delegation boilerplate ---

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
}
