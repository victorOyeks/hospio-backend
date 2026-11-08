package com.hospio.common.tenancy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds the current tenant to the pooled connection as Postgres session settings
 * ({@code app.business_id}, {@code app.hotel_id}, {@code app.scope}) so the row-level policies in each
 * service's schema see only that tenant's rows.
 *
 * The binding happens on the first statement, not when the connection is handed out: Spring opens the
 * connection when a transaction starts, which in Kafka listeners, schedulers and start-up seeders is before
 * {@link TenantContext} is set. Every statement re-checks the tenant and re-binds when a different tenant
 * is now current. A connection never drops back to "no tenant" while it is leased: the commit-time flush
 * runs after the caller has left {@code TenantContext.runWith}, and it must still write as that tenant.
 * Settings are cleared when the connection returns to the pool.
 */
public class RowLevelSecurityDataSource extends DelegatingDataSource {

    private static final String BIND = "SELECT set_config('app.business_id', ?, false), set_config('app.hotel_id', ?, false), set_config('app.scope', ?, false)";
    private static final String RESET = "SELECT set_config('app.business_id', '', false), set_config('app.hotel_id', '', false), set_config('app.scope', '', false)";
    private static final Set<String> STATEMENT_METHODS = Set.of("prepareStatement", "createStatement", "prepareCall", "nativeSQL");

    public RowLevelSecurityDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        Binding binding = new Binding();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        String name = method.getName();
                        if (STATEMENT_METHODS.contains(name)) {
                            binding.apply(connection);
                        } else if ("close".equals(name) && !connection.isClosed()) {
                            binding.reset(connection);
                        } else if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
                            return unwrapAware(connection, method, args, proxy);
                        }
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Object unwrapAware(Connection connection, Method method, Object[] args, Object proxy) throws Throwable {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy)) {
            return "unwrap".equals(method.getName()) ? proxy : Boolean.TRUE;
        }
        return method.invoke(connection, args);
    }

    /** What this connection is currently bound to, so repeated statements for the same tenant cost nothing. */
    private static final class Binding {
        private String bound;

        void apply(Connection connection) throws SQLException {
            Tenant tenant = TenantContext.current().orElse(null);
            String business = tenant == null || tenant.businessId() == null ? "" : tenant.businessId();
            String hotel = tenant == null || tenant.hotelId() == null ? "" : tenant.hotelId();
            String scope = tenant == null || tenant.scope() == null ? "" : tenant.scope();
            String key = business + '|' + hotel + '|' + scope;
            if (Objects.equals(key, bound) || (tenant == null && bound != null)) {
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(BIND)) {
                ps.setString(1, business);
                ps.setString(2, hotel);
                ps.setString(3, scope);
                ps.execute();
            }
            bound = key;
        }

        void reset(Connection connection) {
            if (bound == null) {
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(RESET)) {
                ps.execute();
            } catch (SQLException ignored) {
                // a broken connection is evicted by the pool anyway
            }
            bound = null;
        }
    }
}
