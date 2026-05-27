FROM ghcr.io/osgeo/gdal:ubuntu-small-3.8.3
RUN sed -i 's/archive.ubuntu.com/mirrors.aliyun.com/g' /etc/apt/sources.list && \
    sed -i 's/security.ubuntu.com/mirrors.aliyun.com/g' /etc/apt/sources.list && \
    apt-get update && apt-get install -y --no-install-recommends python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY mcp-servers/requirements.txt /tmp/req.txt
# 系统 pip 较旧且无 --break-system-packages；用 venv 安装 MCP 依赖
RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir --upgrade pip && \
    /opt/venv/bin/pip install --no-cache-dir -r /tmp/req.txt
ENV PATH="/opt/venv/bin:$PATH"
COPY mcp-servers/gdal/server.py /app/server.py
COPY mcp-servers/_common /app/_common
ENV MCP_HOST=0.0.0.0 MCP_PORT=8102
EXPOSE 8102
CMD ["python", "/app/server.py"]
