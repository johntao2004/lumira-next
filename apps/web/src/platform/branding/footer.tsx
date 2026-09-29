import { usePreferences } from "../providers";
import { useBranding } from "./provider";

export function BrandFooter() {
  const branding = useBranding();
  const { t } = usePreferences();

  return (
    <>
      <div>
        {branding.footerCopyright ||
          `© ${branding.copyrightStartYear} ${branding.companyName || branding.websiteName}`}
      </div>
      {branding.footerIcp && <div>{branding.footerIcp}</div>}
      {branding.footerPoliceBeian && <div>{branding.footerPoliceBeian}</div>}
      <div>
        {branding.githubLinkEnabled && (
          <a href={branding.githubLinkUrl} target="_blank" rel="noopener noreferrer">
            GitHub
          </a>
        )}
        {branding.helpLinkEnabled && (
          <a
            href={branding.helpLinkUrl}
            target="_blank"
            rel="noopener noreferrer"
            style={{ marginInlineStart: 16 }}
          >
            {t("helpLink")}
          </a>
        )}
      </div>
    </>
  );
}
