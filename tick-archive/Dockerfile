FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd -u 10001 -m archive && mkdir /data && chown archive:archive /data
COPY archive.py ./
USER archive
ENV TICK_ARCHIVE_DB=/data/trades.sqlite3 TICK_ARCHIVE_HOST=0.0.0.0
EXPOSE 8787
CMD ["python", "-u", "archive.py"]
