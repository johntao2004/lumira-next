package com.lumira.platform.settings;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class MaintenanceSettingsTest {
 @Test void expiresAtExactBoundary(){var end=Instant.parse("2026-09-09T00:00:00Z");var s=new MaintenanceSettings(true,"","",end,List.of());s.validate();assertTrue(s.activeAt(end.minusSeconds(1)));assertFalse(s.activeAt(end));assertFalse(MaintenanceSettings.defaults().activeAt(end));}
 @Test void validatesRoleReferencesAndText(){var id=UUID.randomUUID();assertThrows(ResponseStatusException.class,()->new MaintenanceSettings(true,"","",null,List.of(id,id)).validate());assertThrows(ResponseStatusException.class,()->new MaintenanceSettings(true,"x".repeat(201),"",null,List.of()).validate());}
}
