import { useParams } from "@umijs/max";
import { Workspace } from "@/platform/shell/workspace";
import { isDashboardSection } from "@/platform/shell/routes";
import NotFoundRoute from "../not-found";

export default function DashboardSectionRoute() {
  const { section = "" } = useParams<{ section: string }>();
  if (!isDashboardSection(section)) return <NotFoundRoute />;
  return <Workspace />;
}
