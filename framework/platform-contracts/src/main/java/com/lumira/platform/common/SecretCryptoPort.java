package com.lumira.platform.common;

/**
 * 宿主密钥操作契约；不暴露部署密钥，未配置时加解密调用保持 503。 Host cryptographic operations without deployment-key exposure;
 * missing configuration keeps encryption/decryption unavailable with HTTP 503.
 */
public interface SecretCryptoPort {
    /**
     * 返回部署密钥是否配置；不返回密钥材料。 Reports whether deployment keys are configured without exposing key
     * material.
     */
    boolean configured();

    /**
     * 以用途上下文加密明文字节并返回密文封装；未配置密钥时拒绝。 Encrypts bytes with a purpose context and returns an envelope;
     * rejects missing keys.
     */
    String encrypt(String context, byte[] plaintext);

    /**
     * 按相同用途上下文解密；调用方及时清零返回明文，未配置或验证失败时拒绝。 Decrypts with the matching context; callers clear
     * returned plaintext; missing keys or failed verification are rejected.
     */
    byte[] decrypt(String context, String envelope);

    /**
     * 生成绑定用途上下文的认证摘要，不返回部署密钥。 Computes a context-bound authentication digest without exposing
     * deployment keys.
     */
    String authenticate(String context, byte[] value);

    /**
     * 校验上下文和数据的认证摘要，返回是否匹配。 Verifies the digest for context and data and returns whether it
     * matches.
     */
    boolean matchesAuthentication(String context, byte[] value, String expected);
}
