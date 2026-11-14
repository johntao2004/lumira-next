package com.lumira.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** 向可选内容模块提供最小规范化图片契约。 Minimal normalized-image contract exposed to optional content modules. */
public interface FileContentPort {
    long MAXIMUM_BRANDING_IMAGE_BYTES = 5L * 1024 * 1024;

    /**
     * 按操作者权限保存规范化内嵌图片；读取或图片处理失败抛出 IOException。 Stores a normalized inline image under actor access
     * checks; I/O or image processing failures throw IOException.
     */
    Image uploadInlineImageForContent(
            String actor, String filename, InputStream source, long contentLength)
            throws IOException;

    /**
     * 按品牌槽位显示尺寸上传已清理的公开品牌图片。 Uploads a sanitized image for public platform branding at the slot's
     * display resolution.
     */
    Image uploadBrandingImage(
            String actor, BrandingImageSlot slot, InputStream source, long contentLength)
            throws IOException;

    /**
     * 按操作者权限查询可用图片，校验未就绪或不可访问状态。 Looks up a ready image under actor access checks and validates
     * availability.
     */
    Image findReadyImageForContent(String actor, UUID id);

    /**
     * 仅查询通过品牌上传流程登记的图片。 Looks up a saved branding image; only images registered by the branding
     * upload path qualify.
     */
    Image findReadyBrandingImage(UUID id);

    /**
     * 为当前设置及不可变历史版本保留上传图片。 Retains an uploaded image for the current setting and its immutable
     * history revisions.
     */
    void retainBrandingImage(UUID id);

    /**
     * 读取反馈托管图片；仅供宿主已确认的反馈内容处理路径调用。 Looks up a managed feedback image for host-validated feedback
     * processing.
     */
    Image findManagedFeedbackImageForContent(UUID id);

    /**
     * 打开图片内容流；调用方负责关闭，读取失败抛出 IOException。 Opens image content; the caller closes the stream and I/O
     * failures throw IOException.
     */
    InputStream openContentImage(Image image) throws IOException;

    /**
     * 表达文件校验失败，不暴露 ImageNormalizer 实现。 File-module validation failure expressed without exposing
     * ImageNormalizer.
     */
    final class InvalidImage extends IOException {
        public InvalidImage(String code, Throwable cause) {
            super(code, cause);
        }
    }

    record Image(UUID id, long sizeBytes) {}

    enum BrandingImageSlot {
        FAVICON(256, "favicon"),
        LOGO(1024, "logo"),
        LOGIN_BACKGROUND(2048, "login-background");

        private final int outputEdge;
        private final String filename;

        BrandingImageSlot(int outputEdge, String filename) {
            this.outputEdge = outputEdge;
            this.filename = filename;
        }

        public int outputEdge() {
            return outputEdge;
        }

        public String filename() {
            return filename;
        }
    }
}
