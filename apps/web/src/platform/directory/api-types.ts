export interface UserSummary {
  version: number;
  id: string;
  username: string;
  displayName: string;
  enabled: boolean;
  fileQuotaBytesOverride: number | null;
  fileQuotaFilesOverride: number | null;
}
