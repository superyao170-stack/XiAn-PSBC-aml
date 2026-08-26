FROM python:3.12-slim
WORKDIR /app
COPY app.py /app/app.py
ENV ANALYTICS_HOST=0.0.0.0 ANALYTICS_PORT=18082
EXPOSE 18082
CMD ["python", "/app/app.py"]
