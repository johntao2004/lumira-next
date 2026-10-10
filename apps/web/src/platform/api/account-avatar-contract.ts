export type AccountAvatarData = {
  fileId: string | null;
  version: number;
  generated: boolean;
};

export const accountAvatarKey = ["account-avatar"] as const;
