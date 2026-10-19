/**
 * 校验 dashboard 单段路径格式后进入宿主工作区，页面存在性与访问权限仍由后续路由状态及服务端判断。
 * Validate the dashboard segment format before entering the host workspace, leaving page existence and access decisions to subsequent routing and server checks.
 */
import { useParams } from "@umijs/max";
import { Workspace } from "@/app/dashboard-workspace";
import { isDashboardSection } from "@/platform/shell/routes";
import NotFoundRoute from "../not-found";

export default function DashboardSectionRoute() {
  const { section = "" } = useParams<{ section: string }>();
  if (!isDashboardSection(section)) return <NotFoundRoute />;
  return <Workspace />;
}
