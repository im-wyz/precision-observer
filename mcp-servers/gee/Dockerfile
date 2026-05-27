FROM python:3.11-slim
WORKDIR /app
COPY mcp-servers/requirements.txt /tmp/req.txt
RUN pip install --no-cache-dir -r /tmp/req.txt
COPY agent-api /app/agent-api
ENV PYTHONPATH=/app/agent-api/src
COPY mcp-servers/gee/server.py /app/server.py
COPY mcp-servers/_common /app/_common
RUN mkdir -p /data/gee_exports
EXPOSE 8101
CMD ["python", "/app/server.py"]
