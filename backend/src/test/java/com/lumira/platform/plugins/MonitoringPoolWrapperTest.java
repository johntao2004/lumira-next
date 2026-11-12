package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.monitoring.MonitoringService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MonitoringPoolWrapperTest {
    @Test
    void wrappedPoolKeepsItsOperationalCounters() throws Exception {
        var hikari = mock(HikariDataSource.class);
        var pool = mock(HikariPoolMXBean.class);
        when(hikari.isWrapperFor(HikariDataSource.class)).thenReturn(true);
        when(hikari.unwrap(HikariDataSource.class)).thenReturn(hikari);
        when(hikari.getHikariPoolMXBean()).thenReturn(pool);
        when(pool.getActiveConnections()).thenReturn(7);
        when(pool.getIdleConnections()).thenReturn(17);
        when(pool.getThreadsAwaitingConnection()).thenReturn(2);
        when(hikari.getMaximumPoolSize()).thenReturn(24);
        var service =
                new MonitoringService(null, null, null, new PluginFencedDataSource(hikari), null);
        MonitoringService.DatabaseRuntime value =
                ReflectionTestUtils.invokeMethod(service, "databaseRuntime");
        assertNotNull(value);
        assertEquals(7, value.activeConnections());
        assertEquals(17, value.idleConnections());
        assertEquals(2, value.waitingConnections());
        assertEquals(24, value.maxConnections());
    }
}
