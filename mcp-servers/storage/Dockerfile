FROM python:3.11-slim
WORKDIR /app
COPY mcp-servers/requirements.txt /tmp/req.txt
RUN pip install --no-cache-dir -r /tmp/req.txt
COPY mcp-servers/storage/server.py /app/server.py
COPY mcp-servers/_common /app/_common
EXPOSE 8104
CMD ["python", "/app/server.py"]
