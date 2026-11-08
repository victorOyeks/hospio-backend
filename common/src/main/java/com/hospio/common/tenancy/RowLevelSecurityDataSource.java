package com.hospio.common.tenancy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds the current tenant to every pooled connection as Postgres session settings
 * ({@code app.business_id}, {@code app.hotel_id}, {@code app.scope}) so the row-level policies in each
 * service's schema see only that tenant's rows. Settings are cleared when the connection returns to the pool.
 */
public class RowLevelSecurityDataSource extends DelegatingDataSource {

    private static final String BIND = "SELECT set_config('app.business_id', ?, false), set_config('app.hotel_id', ?, false), set_config('app.scope', ?, false)";
    private static final String RESET = "SELECT set_config('app.business_id', '', false), set_config('app.hotel_id', '', false), set_config('app.scope', '', false)";

    public RowLevelSecurityDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    private Connection bind(Connection connection) throws SQLException {
        Tenant tenant = TenantContext.current().orElse(null);
        try (PreparedStatement ps = connection.prepareStatement(BIND)) {
            ps.setString(1, tenant == null || tenant.businessId() == null ? "" : tenant.businessId());
            ps.setString(2, tenant == null || tenant.hotelId() == null ? "" : tenant.hotelId());
            ps.setString(3, tenant == null ? "" : tenant.scope());
            ps.execute();
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        if ("close".equals(method.getName()) && !connection.isClosed()) {
                            try (PreparedStatement ps = connection.prepareStatement(RESET)) {
                                ps.execute();
                            } catch (SQLException ignored) {
                                // a broken connection is evicted by the pool anyway
                            }
                        }
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
