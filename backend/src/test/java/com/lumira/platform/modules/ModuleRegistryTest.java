package com.lumira.platform.modules;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ModuleRegistryTest {
 PlatformModule module(String id,String...deps){return new PlatformModule(id,id,"1.0.0",List.of(deps));}
 @Test void coreDependenciesMustAlsoBeCore(){assertThrows(IllegalArgumentException.class,()->new ModuleRegistry(List.of(new PlatformModule("core","Core","1.0",List.of("optional"),true),module("optional"))));assertThrows(IllegalArgumentException.class,()->new ModuleRegistry(List.of(module("audit-console","platform"),module("platform"),module("optional")))) ;}
 @Test void auditConsoleIsFixedCoreWithoutPromotingOtherOptionalModules(){var registry=new ModuleRegistry(List.of(module("audit-console","platform"),new PlatformModule("platform","Platform","1.0.0",List.of(),true),module("optional","platform")));assertTrue(registry.require("audit-console").core());assertFalse(registry.require("audit-console").required());assertFalse(registry.require("optional").core());}
 @Test void ordersDependenciesFirst(){var registry=new ModuleRegistry(List.of(module("business","identity"),module("identity")));assertEquals(List.of("identity","business"),registry.modules().stream().map(PlatformModule::id).toList());}
 @Test void rejectsMissingDependencies(){assertThrows(IllegalArgumentException.class,()->new ModuleRegistry(List.of(module("business","missing"))));}
 @Test void rejectsCycles(){assertThrows(IllegalArgumentException.class,()->new ModuleRegistry(List.of(module("a","b"),module("b","a"))));}
 @Test void rejectsDuplicates(){assertThrows(IllegalArgumentException.class,()->new ModuleRegistry(List.of(module("a"),module("a"))));}
}
