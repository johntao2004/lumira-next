package com.lumira.platform.plugins;

import com.lumira.platform.common.PluginWorkFencedException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Keeps plugin SQL, its admission fence and its commit on the same physical connection. */
final class PluginFencedDataSource extends DelegatingDataSource implements AutoCloseable {
    PluginFencedDataSource(DataSource source) {
        super(source);
    }

    @Override
    public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private static Connection wrap(Connection connection) {
        return new Invocation(connection).proxy();
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static final class Invocation {
        final Connection target;
        final Set<PluginInvocationContext.Owner> readers = new HashSet<>();
        final Set<PluginInvocationContext.Owner> writers = new HashSet<>();
        boolean rollbackOnly;
        Connection proxy;

        Invocation(Connection target) {
            this.target = target;
        }

        Connection proxy() {
            proxy =
                    (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    this::connectionMethod);
            return proxy;
        }

        private void clear() {
            readers.clear();
            writers.clear();
            rollbackOnly = false;
        }

        private Object connectionMethod(Object instance, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            if (name.equals("equals")) return instance == args[0];
            if (name.equals("hashCode")) return System.identityHashCode(instance);
            if (name.equals("commit")
                    || (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0]))) {
                if (rollbackOnly) {
                    try {
                        target.rollback();
                    } finally {
                        clear();
                    }
                    throw new PluginWorkFencedException();
                }
                try {
                    return invoke(target, method, args);
                } finally {
                    clear();
                }
            }
            if (name.equals("rollback")) {
                // A savepoint rollback retains the fence/rollback-only status of the whole TX.
                if (args != null && args.length != 0) {
                    Object result = invoke(target, method, args);
                    readers.clear();
                    writers.clear();
                    return result;
                }
                try {
                    return invoke(target, method, args);
                } finally {
                    clear();
                }
            }
            if (name.equals("unwrap") && ((Class<?>) args[0]).isInstance(instance)) return instance;
            if (name.equals("unwrap") && !PluginInvocationContext.owners().isEmpty()) {
                if (target.getAutoCommit()) throw new PluginWorkFencedException();
                enterOwners();
            }
            Object value = invoke(target, method, args);
            if (value instanceof Statement statement)
                return statement(
                        statement,
                        args != null && args.length > 0 && args[0] instanceof String sql
                                ? sql
                                : null);
            return value;
        }

        private Object statement(Statement targetStatement, String preparedSql) {
            var batchRef =
                    new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>();
            Class<?> contract =
                    targetStatement instanceof java.sql.CallableStatement
                            ? java.sql.CallableStatement.class
                            : targetStatement instanceof java.sql.PreparedStatement
                                    ? java.sql.PreparedStatement.class
                                    : Statement.class;
            return Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] {contract},
                    (instance, method, args) -> {
                        if (method.getName().equals("equals")) return instance == args[0];
                        if (method.getName().equals("hashCode"))
                            return System.identityHashCode(instance);
                        if (method.getName().equals("getConnection")) return proxy;
                        if (method.getName().equals("unwrap")
                                && ((Class<?>) args[0]).isInstance(instance)) return instance;
                        if (method.getName().equals("unwrap")
                                && !PluginInvocationContext.owners().isEmpty()) {
                            if (!target.getAutoCommit()) rollbackOnly = true;
                            throw new PluginWorkFencedException();
                        }
                        if (method.getName().equals("addBatch")
                                && args != null
                                && args.length > 0) {
                            var b = batchRef.get();
                            if (b == null) {
                                b = new java.util.ArrayList<>();
                                batchRef.set(b);
                            }
                            b.add((String) args[0]);
                        }
                        if (method.getName().equals("clearBatch")) {
                            var b = batchRef.get();
                            if (b != null) b.clear();
                        }
                        if (method.getName().startsWith("execute")) {
                            String sql =
                                    args != null
                                                    && args.length > 0
                                                    && args[0] instanceof String value
                                            ? value
                                            : preparedSql;
                            var b = batchRef.get();
                            java.util.List<String> batch = b != null ? b : java.util.List.of();
                            try {
                                return execute(targetStatement, method, args, sql, batch);
                            } finally {
                                if (method.getName().contains("Batch")) {
                                    if (b != null) b.clear();
                                    targetStatement.clearBatch();
                                }
                            }
                        }
                        return invoke(targetStatement, method, args);
                    });
        }

        private void enterOwners() throws SQLException {
            boolean readOnly = target.isReadOnly();
            var entered = readOnly ? readers : writers;
            for (var owner : PluginInvocationContext.owners())
                if (!entered.contains(owner)) {
                    try {
                        PluginTransactionFence.enter(target, owner);
                    } catch (SQLException failure) {
                        rollbackOnly = true;
                        throw failure;
                    }
                    entered.add(owner);
                    readers.add(owner);
                }
        }

        private Object execute(
                Statement statement,
                Method method,
                Object[] args,
                String sql,
                java.util.List<String> batch)
                throws Throwable {
            var owners = PluginInvocationContext.owners();
            if (owners.isEmpty() && readers.isEmpty() && !rollbackOnly)
                return invoke(statement, method, args);
            boolean auto = target.getAutoCommit();
            int fetchSize = auto ? statement.getFetchSize() : 0;
            if (auto) target.setAutoCommit(false);
            try {
                if (rollbackOnly) throw new PluginWorkFencedException();
                PluginSqlBoundary.validate(sql);
                for (String item : batch) PluginSqlBoundary.validate(item);
                enterOwners();
                // PostgreSQL normally materializes an auto-commit result even when a fetch size
                // was configured. Do not accidentally create a cursor that our short TX closes.
                if (auto && fetchSize > 0) statement.setFetchSize(0);
                Object result = invoke(statement, method, args);
                if (auto) target.commit();
                return result;
            } catch (Throwable failure) {
                // Mark the whole explicit TX. Catching an admission exception must not let
                // unrelated subsequent SQL commit work from a transaction that lost its fence.
                if (failure instanceof SQLException sqlError
                        && ("40001".equals(sqlError.getSQLState())
                                || "55P03".equals(sqlError.getSQLState()))) rollbackOnly = true;
                if (auto)
                    try {
                        target.rollback();
                    } catch (SQLException rollback) {
                        failure.addSuppressed(rollback);
                    }
                throw failure;
            } finally {
                if (auto) {
                    try {
                        target.setAutoCommit(true);
                        if (fetchSize > 0) statement.setFetchSize(fetchSize);
                    } finally {
                        clear();
                    }
                }
            }
        }
    }
}
