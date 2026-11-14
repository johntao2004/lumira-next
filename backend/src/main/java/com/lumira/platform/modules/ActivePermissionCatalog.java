/**
 * 合并宿主及当前已安装插件的权限目录，并过滤目录外授权，防止卸载后残留记录继续充当有效权限。 Combines host permissions with installed-plugin
 * declarations and filters out other grants so leftover records cannot keep uninstalled permissions
 * active.
 */
package com.lumira.platform.modules;

import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 汇集宿主权限与当前已安装插件声明的权限。 Host permissions plus permissions declared by plugins that are installed
 * right now.
 */
public final class ActivePermissionCatalog {
    private ActivePermissionCatalog() {}

    public static Set<String> current() {
        var permissions = new LinkedHashSet<String>();
        for (PlatformPageId page : PlatformPageId.values())
            permissions.add(page.accessPermission());
        for (PlatformOperation operation : PlatformOperation.values())
            permissions.add(operation.permission());
        permissions.addAll(PluginPermissions.values());
        return Set.copyOf(permissions);
    }

    public static Set<String> retain(Collection<String> authorities) {
        if (authorities == null || authorities.isEmpty()) return Set.of();
        var active = current();
        var kept = new LinkedHashSet<String>();
        for (String authority : authorities)
            if (authority != null && active.contains(authority)) kept.add(authority);
        return Set.copyOf(kept);
    }
}
