# 화면(React + Vite)을 빌드해 nginx로 내보낸다. 빌드 컨텍스트는 frontend/ 폴더다(docker-compose.prod.yml).
# VITE_API_BASE_URL: 화면이 부를 API 주소. 배포에서는 같은 주소의 /api/v1이라 기본값을 그대로 쓴다.
FROM node:20.18.1-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM nginx:1.27.3-alpine
ENV TZ=Asia/Seoul
COPY --from=build /src/dist /usr/share/nginx/html
# 설정 파일(infra/nginx/default.conf)은 compose가 읽기 전용으로 붙인다
EXPOSE 80
