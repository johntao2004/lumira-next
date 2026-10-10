/** Download a binary response without exposing a remote storage URL. */
export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  let link: HTMLAnchorElement | null = null;
  try {
    link = document.createElement("a");
    link.href = url;
    link.download = fileName.replace(/[\\/\r\n\0]/g, "_");
    document.body.append(link);
    link.click();
  } finally {
    link?.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
  }
}
