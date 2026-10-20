#!/usr/bin/env bash
# 生成自签 CA + 服务端证书（TLS + HTTP/2 用）。
#
# 为什么不买证书：用户明确不购买任何服务。自签 CA + App 内置信任 = 强制证书绑定，
# 比公共 CA 更严（公共 CA 里任何一家被攻破都能给你的域名签证书，自签 CA 只有你有）。
#
# 为什么不用 h2c（明文 HTTP/2）：OkHttp 只在 TLS + ALPN 下自动协商 HTTP/2；
# 明文 h2c 必须设 Protocol.H2_PRIOR_KNOWLEDGE，而设了之后就**只**说 h2c、不再回退
# （服务端不支持时直接失败而不是降级）。服务端 Spring 在无 SSL 时把
# server.http2.enabled 映射成 HTTP11+H2C（Upgrade 语义），两者是否互通没实测过。
# 既然本来就要 HTTPS，直接走 TLS + ALPN，OkHttp 零配置。
#
# 用法：
#   ./tools/gen-cert.sh                      # 用默认 SAN（localhost / 10.0.2.2 / 127.0.0.1）
#   ./tools/gen-cert.sh 1.2.3.4              # 加上公网 IP
#   ./tools/gen-cert.sh 1.2.3.4 shop.example.com
#
# 产出：
#   tools/pki/ca.key          CA 私钥（**绝不进 git、绝不离开这台机器**）
#   tools/pki/ca.pem          CA 证书
#   tools/pki/server.key      服务端私钥（拷到服务器）
#   tools/pki/server.pem      服务端证书（拷到服务器）
#   tools/pki/server.p12      PKCS12 keystore（Spring Boot 直接读这个）
#   app/src/main/res/raw/s1_ca.pem   ← App 内置的信任锚（**这个要进 git**）
#
# 换 IP 时只需重跑本脚本并替换服务器上的 server.p12；**CA 不变，App 不用重装**。
set -euo pipefail

# Git Bash / MSYS 会把以斜杠开头的参数当路径去转换：
# `-subj /C=CN/O=x/CN=y` 会变成 `C:/Program Files/Git/C=CN/O=x/CN=y`，openssl 随即报
# "subject name is expected to be in the format /type0=value0/..."。
# 这两个变量关掉转换。在 Linux/macOS 上它们是空操作，所以脚本两边都能跑。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.."
PKI=tools/pki
mkdir -p "$PKI" app/src/main/res/raw

DAYS_CA=3650          # CA 10 年
DAYS_SERVER=1825      # 服务端证书 5 年（比 CA 短，留出轮换余地）
P12PASS=changeit      # 仅用于传输 keystore；服务器上的文件权限才是真正的防线
SAN_EXTRA=("$@")

# ---- 1. CA（幂等：已存在就复用，否则换 IP 重签会让所有已装机的 App 失效）----
if [[ -f "$PKI/ca.key" && -f "$PKI/ca.pem" ]]; then
  echo "[ca] 复用已有 CA: $PKI/ca.pem"
  echo "     （换 IP 不需要重新生成 CA —— 这正是 App 不用重装的原因）"
else
  echo "[ca] 生成新的 CA（10 年）"
  openssl req -x509 -newkey rsa:4096 -sha256 -days "$DAYS_CA" -nodes \
    -keyout "$PKI/ca.key" -out "$PKI/ca.pem" \
    -subj "/C=CN/O=service1-dev/CN=service1 Root CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"
fi

# ---- 2. SAN 列表 ----
SAN="DNS:localhost,IP:127.0.0.1,IP:10.0.2.2"   # 10.0.2.2 = 模拟器访问宿主机
for x in "${SAN_EXTRA[@]:-}"; do
  [[ -z "$x" ]] && continue
  if [[ "$x" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    SAN="$SAN,IP:$x"
  else
    SAN="$SAN,DNS:$x"
  fi
done
echo "[san] $SAN"

# ---- 3. 服务端证书 ----
echo "[srv] 生成服务端密钥与 CSR（$DAYS_SERVER 天）"
openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout "$PKI/server.key" -out "$PKI/server.csr" \
  -subj "/C=CN/O=service1/CN=service1"

cat > "$PKI/server.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$SAN
EOF

openssl x509 -req -in "$PKI/server.csr" -CA "$PKI/ca.pem" -CAkey "$PKI/ca.key" \
  -CAcreateserial -out "$PKI/server.pem" -days "$DAYS_SERVER" -sha256 \
  -extfile "$PKI/server.ext"

# ---- 4. PKCS12（Spring Boot 的 server.ssl.key-store 直接读）----
openssl pkcs12 -export -out "$PKI/server.p12" \
  -inkey "$PKI/server.key" -in "$PKI/server.pem" -certfile "$PKI/ca.pem" \
  -name service1 -passout "pass:$P12PASS"

# ---- 5. App 内置的信任锚 ----
cp "$PKI/ca.pem" app/src/main/res/raw/s1_ca.pem

# ---- 6. 本机 service1 checkout（存在就顺手放好，让 --spring.profiles.active=tls 能直接起）----
# application-tls.properties 里写的是 file:./config/server.p12，相对**服务端进程的工作目录**。
S1_LOCAL="${S1_DIR:-/c/Users/hhl/IdeaProjects/service1}"
if [[ -d "$S1_LOCAL" ]]; then
  mkdir -p "$S1_LOCAL/config"
  cp "$PKI/server.p12" "$S1_LOCAL/config/server.p12"
  echo "[srv] 已复制到本机 service1: $S1_LOCAL/config/server.p12"
fi

echo
echo "==================== 完成 ===================="
openssl x509 -in "$PKI/server.pem" -noout -subject -dates -ext subjectAltName
echo
echo "本机起 TLS + HTTP/2（配置已在 service1 的 application-tls.properties 里）："
echo "  cd $S1_LOCAL && ./gradlew bootRun --args='--spring.profiles.active=tls'"
echo "  curl --http2 -vk https://localhost:8443/api/products/list   # 看 ALPN: server accepted h2"
echo
echo "部署到云主机：把 $PKI/server.p12 放到服务进程的 ./config/server.p12"
echo "（或改 application-tls.properties 里的 server.ssl.key-store 指向绝对路径），"
echo "并在阿里云安全组放行端口（默认 8443，想用 443 就 SERVER_PORT=443 覆盖）。"
echo "⚠ ca.key 绝不进 git、绝不上传服务器（服务器只需要 server.p12）。"
echo "⚠ 生产环境请设 S1_TLS_KEYSTORE_PASSWORD 并用它重新导出 p12，别用 $P12PASS。"
