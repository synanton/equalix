package org.synanton.equalix.integration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * Test-only JDBC proxy that counts executed statements. Wraps the real DataSource via a
 * BeanPostProcessor (see {@code DbLoadBenchmarkTest}); production code is untouched.
 * Counts every {@code execute*()} call on statements (Hibernate without a batch size never
 * batches, so one call equals one server round-trip here).
 */
public final class CountingDataSource {

    private static final AtomicLong COUNT = new AtomicLong();

    private CountingDataSource() {
    }

    public static void reset() {
        COUNT.set(0);
    }

    public static long count() {
        return COUNT.get();
    }

    public static DataSource wrap(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            new DataSourceHandler(delegate));
    }

    private record DataSourceHandler(DataSource delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("getConnection") && (args == null || args.length == 0)) {
                return wrapConnection((Connection) method.invoke(delegate, args));
            }
            // Infrastructure (e.g. Actuator's dbHealthContributor) probes wrapper
            // capabilities; answer from the delegate so boot is unaffected.
            return method.invoke(delegate, args);
        }
    }

    private static Connection wrapConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                Object result = method.invoke(delegate, args);
                return switch (method.getName()) {
                    case "prepareStatement", "prepareCall" ->
                        wrapStatement((PreparedStatement) result);
                    case "createStatement" -> wrapRawStatement((Statement) result);
                    default -> result;
                };
            });
    }

    private static PreparedStatement wrapStatement(PreparedStatement delegate) {
        return (PreparedStatement) Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> {
                if (method.getName().startsWith("execute")) {
                    COUNT.incrementAndGet();
                }
                return method.invoke(delegate, args);
            });
    }

    private static Statement wrapRawStatement(Statement delegate) {
        return (Statement) Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(),
            new Class<?>[]{Statement.class},
            (proxy, method, args) -> {
                if (method.getName().startsWith("execute")) {
                    COUNT.incrementAndGet();
                }
                return method.invoke(delegate, args);
            });
    }
}
