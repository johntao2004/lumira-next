import { useEffect } from "react";
import { LoginPage } from "@/platform/auth/login-page";
import { useBranding } from "@/platform/branding/provider";

export default function LoginRoute() {
  const branding = useBranding();

  useEffect(() => {
    document.title = branding.websiteName;
  }, [branding.websiteName]);

  return <LoginPage />;
}
