import { Link } from "@umijs/max";
import { Button, Result } from "antd";
import { usePreferences } from "@/platform/providers";

export default function NotFoundRoute() {
  const { t } = usePreferences();
  return (
    <main className="workspace-status">
      <Result
        status="404"
        title="404"
        subTitle={t("notFound")}
        extra={<Link to="/dashboard/home"><Button type="primary">{t("backHome")}</Button></Link>}
      />
    </main>
  );
}
