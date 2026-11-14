package com.lumira.platform.common;

import java.util.Optional;

/** 供模块使用加密凭据的秘密存储契约。 Secret-storage contract for modules consuming encrypted credentials. */
public interface SecretStorePort {
    record State(long version, boolean configured) {}

    /**
     * 返回指定用途秘密的版本及是否已配置，不返回明文。 Returns version and configured state for a secret context, without
     * plaintext.
     */
    State state(String context);

    /**
     * 返回调用方持有的短期明文字节，使用后应清零。 Returns owned plaintext bytes for short-lived use; the caller should
     * clear them.
     */
    Optional<byte[]> reveal(String context);

    /** 授权与审计仍由具体调用方负责。 Authorization and audit remain the responsibility of the typed caller. */
    State save(String context, long expectedVersion, byte[] plaintext);
}
