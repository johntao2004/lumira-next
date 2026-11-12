import { LeftOutlined, RightOutlined, ZoomInOutlined, ZoomOutOutlined } from "@ant-design/icons";
import { Alert, Button, Spin } from "antd";
import { useEffect, useRef, useState } from "react";
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from "pdfjs-dist";

const MAX_ZOOM = 4;
const MIN_ZOOM = 0.25;
const MAX_CANVAS_PIXELS = 16_000_000;

function pdfAssetUrl(version: string, path: string) {
  return new URL(`/pdfjs/${version}/${path}`, window.location.origin).toString();
}

export function FilePreviewPdf({ data, title, t, onRetry }: {
  data: Blob;
  title: string;
  t: (key: string, params?: Record<string, string | number>) => string;
  onRetry: () => void;
}) {
  const [pdfDocument, setPdfDocument] = useState<PDFDocumentProxy | null>(null);
  const [pageNumber, setPageNumber] = useState(1);
  const [pageCount, setPageCount] = useState(0);
  const [zoom, setZoom] = useState(1);
  const [fitWidth, setFitWidth] = useState(true);
  const [containerWidth, setContainerWidth] = useState(0);
  const [actualScale, setActualScale] = useState(1);
  const [loading, setLoading] = useState(true);
  const [rendering, setRendering] = useState(false);
  const [failed, setFailed] = useState(false);
  const viewportRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    let cancelled = false;
    let loadingTask: PDFDocumentLoadingTask | undefined;
    setPdfDocument(null);
    setPageNumber(1);
    setPageCount(0);
    setFailed(false);
    setLoading(true);

    void (async () => {
      try {
        const bytes = new Uint8Array(await data.arrayBuffer());
        if (cancelled) return;
        const pdfjs = await import("pdfjs-dist");
        if (cancelled) return;
        const assetUrl = (path: string) => pdfAssetUrl(pdfjs.version, path);
        pdfjs.GlobalWorkerOptions.workerSrc = assetUrl("pdf.worker.min.js");
        loadingTask = pdfjs.getDocument({
          data: bytes,
          cMapUrl: assetUrl("cmaps/"),
          cMapPacked: true,
          iccUrl: assetUrl("iccs/"),
          standardFontDataUrl: assetUrl("standard_fonts/"),
          wasmUrl: assetUrl("wasm/"),
          useWorkerFetch: true,
          useSystemFonts: true,
          disableFontFace: false,
          useWasm: false,
        });
        const loadedDocument = await loadingTask.promise;
        if (cancelled) return;
        setPdfDocument(loadedDocument);
        setPageCount(loadedDocument.numPages);
      } catch {
        if (!cancelled) setFailed(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
      if (loadingTask) void loadingTask.destroy().catch(() => undefined);
    };
  }, [data]);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const updateWidth = () => setContainerWidth(viewport.clientWidth);
    updateWidth();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", updateWidth);
      return () => window.removeEventListener("resize", updateWidth);
    }
    const observer = new ResizeObserver(updateWidth);
    observer.observe(viewport);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!pdfDocument || !canvas || containerWidth <= 0 || failed) return;
    let cancelled = false;
    let renderTask: RenderTask | undefined;
    setRendering(true);

    void (async () => {
      try {
        const page = await pdfDocument.getPage(pageNumber);
        if (cancelled) return;
        const baseViewport = page.getViewport({ scale: 1 });
        const widthScale = fitWidth ? Math.max(0.1, (containerWidth - 32) / baseViewport.width) : 1;
        const scale = Math.max(0.1, Math.min(MAX_ZOOM, widthScale * zoom));
        const pageViewport = page.getViewport({ scale });
        const pixelRatio = Math.min(
          window.devicePixelRatio || 1,
          Math.sqrt(MAX_CANVAS_PIXELS / (pageViewport.width * pageViewport.height)),
        );
        const context = canvas.getContext("2d", { alpha: false });
        if (!context) throw new Error("PDF canvas is unavailable.");
        canvas.width = Math.max(1, Math.ceil(pageViewport.width * pixelRatio));
        canvas.height = Math.max(1, Math.ceil(pageViewport.height * pixelRatio));
        canvas.style.width = `${pageViewport.width}px`;
        canvas.style.height = `${pageViewport.height}px`;
        setActualScale(scale);
        renderTask = page.render({
          canvas,
          canvasContext: context,
          viewport: pageViewport,
          transform: [pixelRatio, 0, 0, pixelRatio, 0, 0],
        });
        await renderTask.promise;
      } catch (error) {
        if (!cancelled && !(error instanceof Error && error.name === "RenderingCancelledException")) {
          setFailed(true);
        }
      } finally {
        if (!cancelled) setRendering(false);
      }
    })();

    return () => {
      cancelled = true;
      renderTask?.cancel();
      canvas.width = 0;
      canvas.height = 0;
      canvas.style.removeProperty("width");
      canvas.style.removeProperty("height");
    };
  }, [pdfDocument, pageNumber, fitWidth, zoom, containerWidth, failed]);

  const controlsDisabled = !pdfDocument || failed;
  const changeZoom = (factor: number) => {
    setZoom(current => Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.round(current * factor * 100) / 100)));
  };

  return <div className="file-preview-pdf-viewer">
    <div className="file-preview-pdf-toolbar" role="toolbar" aria-label={title}>
      <div className="file-preview-pdf-toolbar-group">
        <Button size="small" icon={<LeftOutlined />} aria-label={t("filePreviewPdfPreviousPage")} disabled={controlsDisabled || pageNumber <= 1} onClick={() => setPageNumber(current => Math.max(1, current - 1))} />
        <span className="file-preview-pdf-page" aria-live="polite">{t("filePreviewPdfPageIndicator", { page: pageNumber, pageCount })}</span>
        <Button size="small" icon={<RightOutlined />} aria-label={t("filePreviewPdfNextPage")} disabled={controlsDisabled || pageNumber >= pageCount} onClick={() => setPageNumber(current => Math.min(pageCount, current + 1))} />
      </div>
      <div className="file-preview-pdf-toolbar-group">
        <Button size="small" icon={<ZoomOutOutlined />} aria-label={t("filePreviewPdfZoomOut")} disabled={controlsDisabled} onClick={() => changeZoom(1 / 1.2)} />
        <span className="file-preview-pdf-scale" aria-live="polite">{Math.round(actualScale * 100)}%</span>
        <Button size="small" icon={<ZoomInOutlined />} aria-label={t("filePreviewPdfZoomIn")} disabled={controlsDisabled} onClick={() => changeZoom(1.2)} />
        <Button size="small" type={fitWidth ? "primary" : "default"} aria-label={t("filePreviewPdfFitWidth")} disabled={controlsDisabled} onClick={() => { setFitWidth(true); setZoom(1); }}>{t("filePreviewPdfFitWidth")}</Button>
      </div>
    </div>
    {failed && <Alert type="error" showIcon title={t("filePreviewFailed")} action={<Button type="link" onClick={onRetry}>{t("retry")}</Button>} />}
    <div ref={viewportRef} className="file-preview-pdf-viewport">
      <Spin spinning={loading || rendering} description={t("filePreviewLoading")}>
        <canvas ref={canvasRef} className="file-preview-pdf-canvas" role="img" aria-label={title} />
      </Spin>
    </div>
  </div>;
}
