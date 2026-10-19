# 固定官方 gosu 源码并保留模块校验；仅更新已修补的 Go 编译器。
# Pin upstream gosu source and module verification; update only the patched Go compiler.
FROM golang:1.26.9-alpine3.23 AS gosu-build
RUN apk add --no-cache git
WORKDIR /src/gosu
RUN git init . \
    && git remote add origin https://github.com/tianon/gosu.git \
    && git fetch --depth 1 origin 6456aaa0f3c854d199d0f037f068eb97515b7513 \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = 6456aaa0f3c854d199d0f037f068eb97515b7513 \
    && go mod download \
    && go mod verify \
    && CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=true -ldflags '-d -w' -o /out/gosu . \
    && go version -m /out/gosu | grep -F go1.26.9 \
    && /out/gosu --version | grep -F '1.19'

# 保留 PostgreSQL 18.6 的入口、信号、目录及降权流程；不修改数据库版本或现有卷。
# Preserve PostgreSQL 18.6 entrypoint, signals, directories and privilege drop; do not change data volumes.
FROM postgres:18.6@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336
COPY --from=gosu-build --chown=0:0 --chmod=0755 /out/gosu /usr/local/bin/gosu
# 删除发行镜像的默认测试证书，不配置真实 TLS；现场须独立提供有效证书。
# Remove bundled test certificates, without configuring real TLS; deployment needs separately managed certificates.
RUN rm -f /etc/ssl/private/ssl-cert-snakeoil.key /etc/ssl/certs/ssl-cert-snakeoil.pem \
    && test "$(gosu postgres id -u)" = 999 \
    && test "$(gosu postgres id -g)" = 999 \
    && test "$(stat -c '%a' /usr/local/bin/gosu)" = 755
