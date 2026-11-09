# infra: 로컬 개발용 PostgreSQL · Redis · Kafka

Docker Compose 파일 하나로 팀원 모두 같은 버전의 DB와 Kafka를 띄웁니다.
각자 PC에 PostgreSQL·Redis·Kafka를 따로 설치하지 않습니다.

| 서비스 | 이미지 | 내 PC에서 접속 | 컨테이너끼리 접속 |
| --- | --- | --- | --- |
| PostgreSQL | `postgres:15.19` | `localhost:5432` | `postgres:5432` |
| Redis | `redis:7.2.17` | `localhost:6379` | `redis:6379` |
| Kafka (KRaft, 브로커 1대) | `apache/kafka:3.7.2` | `localhost:9092` | `kafka:29092` |
| Kafka UI (선택) | `provectuslabs/kafka-ui:v0.7.2` | http://localhost:8085 | - |

- 포트는 모두 `127.0.0.1`에만 열려 있어서 같은 네트워크의 다른 PC에서는 접속할 수 없습니다.
- Kafka 토픽 3개 `market.tick`, `market.orderbook`, `order.log`(각각 파티션 3개, 메시지 24시간 보관)는 처음 실행할 때 자동으로 만들어집니다. 메시지 형식은 [docs/contracts/kafka-topics.md](../docs/contracts/kafka-topics.md)를 보세요.
- Kafka는 실시간 시세 전달에만 씁니다. 주문 처리에는 쓰지 않습니다.

## 처음 한 번만

저장소 최상위 폴더(`stockshield/`)에서 실행합니다.

```bash
cp infra/.env.example infra/.env
```

`infra/.env`를 열어 `change-me-...` 비밀번호를 바꿉니다. 팀이 같은 값을 쓰려면 팀장에게 개인 메시지로 받으세요.
`infra/.env`는 `.gitignore`에 등록되어 있어 Git에 올라가지 않습니다.

## 실행 · 확인 · 종료

```bash
docker compose -f infra/docker-compose.yml up -d
```

```bash
docker compose -f infra/docker-compose.yml ps
```

`postgres`, `redis`, `kafka`가 모두 `healthy`면 준비 완료입니다. `kafka-init`은 토픽을 만들고 `Exited (0)`으로 끝나는 것이 정상입니다.

```bash
docker compose -f infra/docker-compose.yml stop
```

| 하고 싶은 것 | 명령어 |
| --- | --- |
| 멈추기 (데이터 유지) | `docker compose -f infra/docker-compose.yml stop` |
| 컨테이너 삭제 (DB·Redis 데이터는 유지, Kafka 메시지는 삭제) | `docker compose -f infra/docker-compose.yml down` |
| 데이터까지 모두 초기화 | `docker compose -f infra/docker-compose.yml down -v` |
| Kafka UI 같이 띄우기 | `docker compose -f infra/docker-compose.yml --profile tools up -d` |
| 로그 보기 | `docker compose -f infra/docker-compose.yml logs -f kafka` |

## DBeaver 접속 정보

새 연결 → PostgreSQL

| 항목 | 값 |
| --- | --- |
| Host | `localhost` |
| Port | `5432` |
| Database | `infra/.env`의 `POSTGRES_DB` (기본 `stockshield`) |
| Username | `infra/.env`의 `POSTGRES_USER` (기본 `stockshield`) |
| Password | `infra/.env`의 `POSTGRES_PASSWORD` |

처음 연결할 때 DBeaver가 PostgreSQL 드라이버를 내려받겠냐고 물으면 **Download**를 누릅니다.
테이블은 여기서 만들지 않습니다. 백엔드(Spring Boot)가 테이블정의서 기준으로 만듭니다.

## Spring Boot · Python에서 쓰는 접속 설정 예시

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/stockshield
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD}
  kafka:
    bootstrap-servers: localhost:9092
```

앱을 Docker 컨테이너로 같은 Compose에 넣을 때는 `localhost` 대신 `postgres`, `redis`, `kafka:29092`를 씁니다.

## Kafka 동작 확인

> **Git Bash를 쓴다면** 아래 `docker exec` 명령 앞에 `MSYS_NO_PATHCONV=1 `을 붙이세요.
> Git Bash가 `/opt/kafka/...` 경로를 `C:/Program Files/Git/opt/...`로 바꿔서 `no such file or directory` 오류가 납니다.
> PowerShell에서는 그대로 실행하면 됩니다.

토픽 목록:

```bash
docker exec stockshield-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
```

`market.tick`에 들어오는 메시지 실시간 보기 (Ctrl+C로 종료):

```bash
docker exec -it stockshield-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic market.tick --property print.key=true
```

시험 메시지 보내기 (`종목코드:내용` 형식으로 입력, Ctrl+C로 종료):

```bash
docker exec -it stockshield-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic market.tick --property parse.key=true --property key.separator=:
```

## 문제가 생겼을 때

| 증상 | 원인과 해결 |
| --- | --- |
| `POSTGRES_PASSWORD 를 설정하세요` 오류 | `infra/.env`가 없습니다. "처음 한 번만"을 먼저 하세요 |
| `port is already allocated` | 그 포트를 다른 프로그램이 쓰고 있습니다. `infra/.env`에서 `POSTGRES_PORT` 등을 바꾸세요 (예: 5433) |
| Docker Desktop에 `Engine stopped` | Docker Desktop을 실행하고 왼쪽 아래 `Engine running`을 확인하세요 |
| 비밀번호를 바꿨는데 DB 접속이 안 됨 | PostgreSQL 비밀번호는 처음 만들 때만 적용됩니다. `down -v`로 초기화한 뒤 다시 실행하세요 (데이터가 지워집니다) |
| `docker exec` 실행 시 `C:/Program Files/Git/opt/...: no such file or directory` | Git Bash의 경로 자동 변환 때문입니다. 명령 앞에 `MSYS_NO_PATHCONV=1 `을 붙이거나 PowerShell에서 실행하세요 |
| Kafka가 `unhealthy` | `logs -f kafka`로 오류를 확인하세요. 메모리가 부족하면 Docker Desktop 설정에서 메모리를 늘리세요 |
