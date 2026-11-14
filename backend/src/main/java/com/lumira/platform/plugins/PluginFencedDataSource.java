package com.lumira.platform.plugins;

import com.lumira.platform.common.PluginWorkFencedException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Keeps plugin SQL, its admission fence and its commit on the same physical connection using hidden
 * bytecode-compiled JDBC wrappers with zero reflective dispatch on hot paths.
 */
final class PluginFencedDataSource extends DelegatingDataSource implements AutoCloseable {
    private static final MethodHandle NEW_CONNECTION;
    private static final MethodHandle NEW_STATEMENT;
    private static final MethodHandle NEW_PREPARED_STATEMENT;
    private static final MethodHandle NEW_CALLABLE_STATEMENT;

    static {
        try {
            var lookup = MethodHandles.lookup();
            NEW_CONNECTION =
                    buildWrapperFactory(
                                    lookup,
                                    "FencedConnectionGen",
                                    ConnectionBase.class,
                                    Connection.class,
                                    "target",
                                    MethodType.methodType(void.class, Connection.class))
                            .asType(MethodType.methodType(Connection.class, Connection.class));
            NEW_STATEMENT =
                    buildWrapperFactory(
                                    lookup,
                                    "FencedStatementGen",
                                    StatementBase.class,
                                    Statement.class,
                                    "statement",
                                    MethodType.methodType(
                                            void.class,
                                            ConnectionBase.class,
                                            Statement.class,
                                            String.class))
                            .asType(
                                    MethodType.methodType(
                                            Statement.class,
                                            ConnectionBase.class,
                                            Statement.class,
                                            String.class));
            NEW_PREPARED_STATEMENT =
                    buildWrapperFactory(
                                    lookup,
                                    "FencedPreparedStatementGen",
                                    PreparedStatementBase.class,
                                    PreparedStatement.class,
                                    "prepared",
                                    MethodType.methodType(
                                            void.class,
                                            ConnectionBase.class,
                                            PreparedStatement.class,
                                            String.class))
                            .asType(
                                    MethodType.methodType(
                                            PreparedStatement.class,
                                            ConnectionBase.class,
                                            PreparedStatement.class,
                                            String.class));
            NEW_CALLABLE_STATEMENT =
                    buildWrapperFactory(
                                    lookup,
                                    "FencedCallableStatementGen",
                                    CallableStatementBase.class,
                                    CallableStatement.class,
                                    "callable",
                                    MethodType.methodType(
                                            void.class,
                                            ConnectionBase.class,
                                            CallableStatement.class,
                                            String.class))
                            .asType(
                                    MethodType.methodType(
                                            CallableStatement.class,
                                            ConnectionBase.class,
                                            CallableStatement.class,
                                            String.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    PluginFencedDataSource(DataSource source) {
        super(source);
    }

    @Override
    public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(super.getConnection(username, password));
    }

    private static Connection wrapConnection(Connection connection) {
        try {
            return (Connection) NEW_CONNECTION.invokeExact(connection);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    static Statement wrapStatement(ConnectionBase owner, Statement statement) {
        try {
            return (Statement) NEW_STATEMENT.invokeExact(owner, statement, (String) null);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    static PreparedStatement wrapPrepared(
            ConnectionBase owner, PreparedStatement statement, String sql) {
        try {
            return (PreparedStatement) NEW_PREPARED_STATEMENT.invokeExact(owner, statement, sql);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    static CallableStatement wrapCallable(
            ConnectionBase owner, CallableStatement statement, String sql) {
        try {
            return (CallableStatement) NEW_CALLABLE_STATEMENT.invokeExact(owner, statement, sql);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    @FunctionalInterface
    interface SqlAction<R> {
        R run() throws SQLException;
    }

    abstract static class ConnectionBase {
        protected final Connection target;
        final Set<PluginInvocationContext.Owner> readers = new HashSet<>();
        final Set<PluginInvocationContext.Owner> writers = new HashSet<>();
        boolean rollbackOnly;

        protected ConnectionBase(Connection target) {
            this.target = target;
        }

        final void clear() {
            readers.clear();
            writers.clear();
            rollbackOnly = false;
        }

        final void enterOwners() throws SQLException {
            boolean readOnly = target.isReadOnly();
            var entered = readOnly ? readers : writers;
            for (var owner : PluginInvocationContext.owners()) {
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
        }

        private void abortFencedCommit() throws SQLException {
            try {
                target.rollback();
            } finally {
                clear();
            }
            throw new PluginWorkFencedException();
        }

        public final void commit() throws SQLException {
            if (rollbackOnly) abortFencedCommit();
            try {
                target.commit();
            } finally {
                clear();
            }
        }

        public final void setAutoCommit(boolean autoCommit) throws SQLException {
            if (!autoCommit) {
                target.setAutoCommit(false);
                return;
            }
            if (rollbackOnly) abortFencedCommit();
            try {
                target.setAutoCommit(true);
            } finally {
                clear();
            }
        }

        public final void rollback() throws SQLException {
            try {
                target.rollback();
            } finally {
                clear();
            }
        }

        public final void rollback(Savepoint savepoint) throws SQLException {
            // A savepoint rollback retains the fence/rollback-only status of the whole TX.
            target.rollback(savepoint);
            readers.clear();
            writers.clear();
        }

        public final <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            if (!PluginInvocationContext.owners().isEmpty()) {
                if (target.getAutoCommit()) throw new PluginWorkFencedException();
                enterOwners();
            }
            return target.unwrap(iface);
        }

        public final Statement createStatement() throws SQLException {
            return wrapStatement(this, target.createStatement());
        }

        public final Statement createStatement(int type, int concurrency) throws SQLException {
            return wrapStatement(this, target.createStatement(type, concurrency));
        }

        public final Statement createStatement(int type, int concurrency, int holdability)
                throws SQLException {
            return wrapStatement(this, target.createStatement(type, concurrency, holdability));
        }

        public final PreparedStatement prepareStatement(String sql) throws SQLException {
            return wrapPrepared(this, target.prepareStatement(sql), sql);
        }

        public final PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
                throws SQLException {
            return wrapPrepared(this, target.prepareStatement(sql, autoGeneratedKeys), sql);
        }

        public final PreparedStatement prepareStatement(String sql, int[] columnIndexes)
                throws SQLException {
            return wrapPrepared(this, target.prepareStatement(sql, columnIndexes), sql);
        }

        public final PreparedStatement prepareStatement(String sql, String[] columnNames)
                throws SQLException {
            return wrapPrepared(this, target.prepareStatement(sql, columnNames), sql);
        }

        public final PreparedStatement prepareStatement(String sql, int type, int concurrency)
                throws SQLException {
            return wrapPrepared(this, target.prepareStatement(sql, type, concurrency), sql);
        }

        public final PreparedStatement prepareStatement(
                String sql, int type, int concurrency, int holdability) throws SQLException {
            return wrapPrepared(
                    this, target.prepareStatement(sql, type, concurrency, holdability), sql);
        }

        public final CallableStatement prepareCall(String sql) throws SQLException {
            return wrapCallable(this, target.prepareCall(sql), sql);
        }

        public final CallableStatement prepareCall(String sql, int type, int concurrency)
                throws SQLException {
            return wrapCallable(this, target.prepareCall(sql, type, concurrency), sql);
        }

        public final CallableStatement prepareCall(
                String sql, int type, int concurrency, int holdability) throws SQLException {
            return wrapCallable(this, target.prepareCall(sql, type, concurrency, holdability), sql);
        }
    }

    abstract static class StatementBase {
        protected final ConnectionBase owner;
        protected final Statement statement;
        protected final String preparedSql;
        private List<String> batch;

        protected StatementBase(ConnectionBase owner, Statement statement, String preparedSql) {
            this.owner = owner;
            this.statement = statement;
            this.preparedSql = preparedSql;
        }

        public final Connection getConnection() {
            return (Connection) owner;
        }

        public final <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            if (!PluginInvocationContext.owners().isEmpty()) {
                if (!owner.target.getAutoCommit()) owner.rollbackOnly = true;
                throw new PluginWorkFencedException();
            }
            return statement.unwrap(iface);
        }

        public final void addBatch(String sql) throws SQLException {
            if (batch == null) batch = new ArrayList<>();
            batch.add(sql);
            statement.addBatch(sql);
        }

        public final void clearBatch() throws SQLException {
            if (batch != null) batch.clear();
            statement.clearBatch();
        }

        protected final <R> R runFenced(String sql, boolean clearBatchOnExit, SqlAction<R> action)
                throws SQLException {
            try {
                var owners = PluginInvocationContext.owners();
                if (owners.isEmpty() && owner.readers.isEmpty() && !owner.rollbackOnly) {
                    return action.run();
                }
                Connection raw = owner.target;
                boolean auto = raw.getAutoCommit();
                int fetchSize = auto ? statement.getFetchSize() : 0;
                if (auto) raw.setAutoCommit(false);
                try {
                    if (owner.rollbackOnly) throw new PluginWorkFencedException();
                    PluginSqlBoundary.validate(sql);
                    if (batch != null) {
                        for (String item : batch) PluginSqlBoundary.validate(item);
                    }
                    owner.enterOwners();
                    // PostgreSQL normally materializes an auto-commit result even when a fetch size
                    // was configured. Do not accidentally create a cursor that our short TX closes.
                    if (auto && fetchSize > 0) statement.setFetchSize(0);
                    R result = action.run();
                    if (auto) raw.commit();
                    return result;
                } catch (Throwable failure) {
                    // Mark the whole explicit TX. Catching an admission exception must not let
                    // unrelated subsequent SQL commit work from a transaction that lost its fence.
                    if (failure instanceof SQLException sqlError
                            && ("40001".equals(sqlError.getSQLState())
                                    || "55P03".equals(sqlError.getSQLState()))) {
                        owner.rollbackOnly = true;
                    }
                    if (auto) {
                        try {
                            raw.rollback();
                        } catch (SQLException rollback) {
                            failure.addSuppressed(rollback);
                        }
                    }
                    if (failure instanceof SQLException sqlFailure) throw sqlFailure;
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    if (failure instanceof Error err) throw err;
                    throw new SQLException(failure);
                } finally {
                    if (auto) {
                        try {
                            raw.setAutoCommit(true);
                            if (fetchSize > 0) statement.setFetchSize(fetchSize);
                        } finally {
                            owner.clear();
                        }
                    }
                }
            } finally {
                if (clearBatchOnExit) {
                    if (batch != null) batch.clear();
                    statement.clearBatch();
                }
            }
        }

        public final ResultSet executeQuery(String sql) throws SQLException {
            return runFenced(sql, false, () -> statement.executeQuery(sql));
        }

        public final boolean execute(String sql) throws SQLException {
            return runFenced(sql, false, () -> statement.execute(sql));
        }

        public final boolean execute(String sql, int flags) throws SQLException {
            return runFenced(sql, false, () -> statement.execute(sql, flags));
        }

        public final boolean execute(String sql, int[] indexes) throws SQLException {
            return runFenced(sql, false, () -> statement.execute(sql, indexes));
        }

        public final boolean execute(String sql, String[] names) throws SQLException {
            return runFenced(sql, false, () -> statement.execute(sql, names));
        }

        public final int executeUpdate(String sql) throws SQLException {
            return runFenced(sql, false, () -> statement.executeUpdate(sql));
        }

        public final int executeUpdate(String sql, int flags) throws SQLException {
            return runFenced(sql, false, () -> statement.executeUpdate(sql, flags));
        }

        public final int executeUpdate(String sql, int[] indexes) throws SQLException {
            return runFenced(sql, false, () -> statement.executeUpdate(sql, indexes));
        }

        public final int executeUpdate(String sql, String[] names) throws SQLException {
            return runFenced(sql, false, () -> statement.executeUpdate(sql, names));
        }

        public final long executeLargeUpdate(String sql) throws SQLException {
            return runFenced(sql, false, () -> statement.executeLargeUpdate(sql));
        }

        public final long executeLargeUpdate(String sql, int flags) throws SQLException {
            return runFenced(sql, false, () -> statement.executeLargeUpdate(sql, flags));
        }

        public final long executeLargeUpdate(String sql, int[] indexes) throws SQLException {
            return runFenced(sql, false, () -> statement.executeLargeUpdate(sql, indexes));
        }

        public final long executeLargeUpdate(String sql, String[] names) throws SQLException {
            return runFenced(sql, false, () -> statement.executeLargeUpdate(sql, names));
        }

        public final int[] executeBatch() throws SQLException {
            return runFenced(preparedSql, true, statement::executeBatch);
        }

        public final long[] executeLargeBatch() throws SQLException {
            return runFenced(preparedSql, true, statement::executeLargeBatch);
        }
    }

    abstract static class PreparedStatementBase extends StatementBase {
        protected final PreparedStatement prepared;

        protected PreparedStatementBase(
                ConnectionBase owner, PreparedStatement statement, String preparedSql) {
            super(owner, statement, preparedSql);
            this.prepared = statement;
        }

        public final ResultSet executeQuery() throws SQLException {
            return runFenced(preparedSql, false, prepared::executeQuery);
        }

        public final boolean execute() throws SQLException {
            return runFenced(preparedSql, false, prepared::execute);
        }

        public final int executeUpdate() throws SQLException {
            return runFenced(preparedSql, false, prepared::executeUpdate);
        }

        public final long executeLargeUpdate() throws SQLException {
            return runFenced(preparedSql, false, prepared::executeLargeUpdate);
        }
    }

    abstract static class CallableStatementBase extends PreparedStatementBase {
        protected final CallableStatement callable;

        protected CallableStatementBase(
                ConnectionBase owner, CallableStatement statement, String preparedSql) {
            super(owner, statement, preparedSql);
            this.callable = statement;
        }
    }

    private static MethodHandle buildWrapperFactory(
            MethodHandles.Lookup lookup,
            String simpleName,
            Class<?> baseClass,
            Class<?> contract,
            String fieldName,
            MethodType ctorType)
            throws ReflectiveOperationException {
        String pkg = PluginFencedDataSource.class.getPackageName();
        ClassDesc generatedDesc = ClassDesc.of(pkg, simpleName);
        ClassDesc baseDesc = ClassDesc.ofDescriptor(baseClass.descriptorString());
        ClassDesc contractDesc = ClassDesc.ofDescriptor(contract.descriptorString());
        MethodTypeDesc ctorDesc = MethodTypeDesc.ofDescriptor(ctorType.toMethodDescriptorString());

        byte[] bytecode =
                ClassFile.of()
                        .build(
                                generatedDesc,
                                clb -> {
                                    clb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
                                    clb.withSuperclass(baseDesc);
                                    clb.withInterfaceSymbols(contractDesc);
                                    clb.withMethodBody(
                                            ConstantDescs.INIT_NAME,
                                            ctorDesc,
                                            0,
                                            cb -> {
                                                cb.aload(0);
                                                int slot = 1;
                                                for (Class<?> param : ctorType.parameterArray()) {
                                                    TypeKind kind = TypeKind.from(param);
                                                    cb.loadLocal(kind, slot);
                                                    slot += kind.slotSize();
                                                }
                                                cb.invokespecial(
                                                        baseDesc,
                                                        ConstantDescs.INIT_NAME,
                                                        ctorDesc);
                                                cb.return_();
                                            });

                                    Set<String> emitted = new HashSet<>();
                                    for (Method method : contract.getMethods()) {
                                        if (Modifier.isStatic(method.getModifiers())) continue;
                                        MethodTypeDesc mDesc =
                                                MethodTypeDesc.ofDescriptor(
                                                        MethodType.methodType(
                                                                        method.getReturnType(),
                                                                        method.getParameterTypes())
                                                                .toMethodDescriptorString());
                                        String key = method.getName() + mDesc.descriptorString();
                                        if (!emitted.add(key)) continue;
                                        if (hasConcreteImplementation(baseClass, method)) continue;
                                        if (method.getName().startsWith("execute")) {
                                            throw new IllegalStateException(
                                                    "Unfenced execute method: " + method);
                                        }
                                        if (contract == Connection.class
                                                && Statement.class.isAssignableFrom(
                                                        method.getReturnType())) {
                                            throw new IllegalStateException(
                                                    "Unwrapped statement factory: " + method);
                                        }
                                        clb.withMethodBody(
                                                method.getName(),
                                                mDesc,
                                                ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL,
                                                cb -> {
                                                    cb.aload(0);
                                                    cb.getfield(baseDesc, fieldName, contractDesc);
                                                    int slot = 1;
                                                    for (Class<?> param :
                                                            method.getParameterTypes()) {
                                                        TypeKind kind = TypeKind.from(param);
                                                        cb.loadLocal(kind, slot);
                                                        slot += kind.slotSize();
                                                    }
                                                    cb.invokeinterface(
                                                            contractDesc, method.getName(), mDesc);
                                                    cb.return_(
                                                            TypeKind.from(method.getReturnType()));
                                                });
                                    }
                                });
        var hidden =
                lookup.defineHiddenClass(bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE);
        return hidden.findConstructor(hidden.lookupClass(), ctorType);
    }

    private static boolean hasConcreteImplementation(Class<?> type, Method interfaceMethod) {
        try {
            Method found =
                    type.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
            return !Modifier.isAbstract(found.getModifiers())
                    && !found.getDeclaringClass().isInterface();
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }
}
