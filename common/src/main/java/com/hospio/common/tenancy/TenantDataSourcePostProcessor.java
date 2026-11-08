package com.hospio.common.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/** Wraps the auto-configured pool so JPA, JdbcTemplate and Liquibase all go through the tenant binding. */
@Component
public class TenantDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource ds && "dataSource".equals(beanName) && !(bean instanceof RowLevelSecurityDataSource)) {
            return new RowLevelSecurityDataSource(ds);
        }
        return bean;
    }
}
