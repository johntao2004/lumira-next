"use client";

import { useState } from "react";
import { Button, Checkbox, Modal, Space, Typography } from "antd";
import { usePreferences } from "@/platform/providers";

export function RecoveryCodes({ codes, onClose }: { codes: string[]; onClose: () => void }) {
  const { t } = usePreferences();
  const [saved, setSaved] = useState(false);
  const contents = `${codes.join("\n")}\n`;

  function download() {
    const url = URL.createObjectURL(new Blob([contents], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "lumira-recovery-codes.txt";
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
  }

  return (
    <Modal
      open
      title={t("mfaRecovery")}
      closable={false}
      maskClosable={false}
      keyboard={false}
      footer={
        <Button type="primary" disabled={!saved} onClick={onClose}>
          {t("close")}
        </Button>
      }
    >
      <Typography.Paragraph>{t("mfaRecoveryHint")}</Typography.Paragraph>
      <Typography.Paragraph copyable={{ text: contents.trimEnd() }}>
        <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontSize: 12 }}>{contents}</pre>
      </Typography.Paragraph>
      <Space orientation="vertical">
        <Button onClick={download}>{t("mfaDownload")}</Button>
        <Checkbox checked={saved} onChange={event => setSaved(event.target.checked)}>
          {t("mfaRecoverySaved")}
        </Checkbox>
      </Space>
    </Modal>
  );
}
