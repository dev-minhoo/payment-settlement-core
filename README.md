# payment-settlement-core

돈이 오가는 시스템에서 **"두 번 나가지 않고, 사라지지 않게"** 를 직접 구현하고 테스트로 증명하는 개인 프로젝트입니다.
P2P 금융 플랫폼 실무에서 겪은 정합성 문제(중복 이체, 공용 계좌 잔액 덮어쓰기)를 실무 코드 없이 문제만 가져와 새로 만들었습니다.

`Java 17` `Spring Boot` `JPA` `MySQL 8` `Redis 7` `Docker` `JUnit 5` `AWS EC2` `AWS RDS`

---

## 1. 결제 중복 방지: 3중 방어

같은 결제 요청이 재시도나 더블 클릭으로 여러 번 들어와도 결제는 한 번만 일어나야 합니다.

| 단계 | 방법 | 막는 것 |
|---|---|---|
| ① DB 조회 | 멱등성 키로 이미 완료된 결제를 찾으면 기존 결과 반환 | 순차 재요청 |
| ② Redis 선점 | `SET NX EX` 로 처리 구간 선점, 해제는 Lua 스크립트로 소유 토큰 확인 후 원자적 삭제 | 동시 요청 |
| ③ DB 유니크 제약 | `idempotency_key` 유니크 제약 | 락 TTL 만료 후 끼어든 요청 (최종 방어선) |

Redis 락은 TTL 때문에 단독으로는 보장이 아니므로, 마지막 판단은 DB 제약에 맡깁니다.

**검증** (`PaymentServiceTest`, `IdempotencyServiceTest`)
- 같은 키로 순차 2회 요청 → 두 번째는 `DUPLICATED`, 같은 결제 ID 반환
- 같은 키로 동시 50건 요청 → 결제 1건 저장
- 동시 100스레드 선점 시도 → 1건만 성공, 남의 토큰으로는 해제 불가

## 2. 잔액 동시 차감: lost update 재현과 세 가지 방어

같은 계좌에서 동시에 출금하면, "잔액 읽기 → 계산 → 저장" 사이에 다른 요청이 끼어 앞선 차감을 덮어씁니다.
먼저 문제를 **실패하는 테스트로 재현**한 뒤, 세 가지 방식으로 막았습니다.

| 방식 | 구현 | 정합성 | 특징 | 쓰는 곳 |
|---|---|---|---|---|
| 읽고-덮어쓰기 (문제) | `SELECT balance` → 계산 → `UPDATE SET balance = ?` | ✗ 출금 유실 | — | 쓰면 안 됨 |
| 조건부 UPDATE | `UPDATE ... SET balance = balance - ? WHERE id = ? AND balance >= ?` + 영향 행 수 확인 | ✓ | 검사와 차감이 한 문장. 가장 단순하고 빠름 | 차감 규칙이 SQL 한 줄로 표현될 때 |
| 비관적 락 | `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) 후 엔티티에서 차감 | ✓ | 대기는 생기지만 재시도 없음. 도메인 로직을 엔티티에 둘 수 있음 | 충돌이 잦고, 차감 전 검증 로직이 복잡할 때 |
| 낙관적 락 + 재시도 | `@Version` 충돌 시 트랜잭션 밖에서 새 트랜잭션으로 재시도 | ✓ | 충돌이 많으면 재시도 비용이 커짐 | 충돌이 드문 수정 (설정·프로필 등) |

**검증** (`AccountConcurrencyTest`, 초기 잔액 100,000원에 1,000원 출금 100건 동시 실행)

| 방식 | 결과 |
|---|---|
| 읽고-덮어쓰기 | 성공 응답 100건인데 잔액 90,000원 남음 → 출금 90건 유실 |
| 조건부 UPDATE | 잔액 0원 |
| 비관적 락 | 잔액 0원 |
| 낙관적 락 + 재시도 | 잔액 0원, 100건 처리에 총 902번 시도 (재시도 802번) |

추가로, 잔액 10,000원 계좌에 1,000원 출금 30건을 동시에 보내면 조건부 UPDATE·비관적 락 모두 **정확히 10건만 성공하고 잔액은 음수가 되지 않습니다.**

**구현하며 정리한 점**
- 낙관적 락 재시도는 반드시 트랜잭션 **밖**에서 해야 합니다. 안에서 재시도하면 오래된 version을 가진 같은 영속성 컨텍스트로 다시 시도하게 됩니다. (`OptimisticWithdrawFacade`)
- JPQL bulk update는 `@Version`을 자동으로 올리지 않습니다. 조건부 UPDATE에서 version을 직접 올려, 다른 경로와 섞여도 낙관적 락이 깨지지 않게 했습니다.
- 잔액 부족은 재시도해도 결과가 같으므로 재시도 대상에서 뺐습니다.
- 같은 행에 충돌이 몰리면 낙관적 락은 100건 처리에 902번 시도(약 9배)가 필요했습니다. 잔액처럼 한 행에 쓰기가 몰리는 곳은 조건부 UPDATE나 비관적 락이 맞고, 낙관적 락은 충돌이 드문 수정에 둡니다.

## 3. 배포 구성 (AWS)

[Client] → EC2(Spring Boot + Redis/Docker) → RDS MySQL 8.4 (퍼블릭 액세스 OFF)

| 구분 | 내용 |
|---|---|
| 리전 | ap-southeast-2 (계정 기본 리전) |
| 컴퓨팅 | EC2 t3.micro, Amazon Linux 2023, Corretto 17, systemd로 jar 실행 (`-Xmx384m`, 스왑 2GB) |
| DB | RDS MySQL 8.4 db.t4g.micro, 단일 AZ, 퍼블릭 액세스 비활성 |
| 캐시 | Redis 7 (EC2 내 Docker, `127.0.0.1` 바인딩, `maxmemory-policy noeviction`) |
| 헬스체크 | `/actuator/health` (DB·Redis 연결 포함, 상세 정보 비노출) |

**네트워크·보안**
- `psc-ec2-sg`: 22(SSH)·8080 인바운드를 관리자 IP(/32)로만 허용
- `psc-rds-sg`: 3306 인바운드를 **`psc-ec2-sg` 참조**로만 허용 (IP가 아닌 보안 그룹 체이닝)
- DB 직접 접근은 EC2를 경유 (배스천 패턴)
- 비용상 NAT 게이트웨이 없이 기본 VPC 사용. 운영 환경이라면 RDS를 프라이빗 서브넷으로 분리

**설정·비밀 정보**
- 접속 정보는 환경변수로 주입 (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`)
- EC2의 `/etc/psc/psc.env`(root 소유, 600)를 systemd `EnvironmentFile`로 로드. 저장소에는 [`deploy/psc.env.example`](deploy/psc.env.example)만 포함
- 서비스 정의: [`deploy/psc.service`](deploy/psc.service)
- 개선 방향: SSM Parameter Store / Secrets Manager + IAM 역할

**Redis를 `noeviction`으로 둔 이유**
- Redis는 결제 중복 방지 선점 락으로 쓰입니다. 메모리 부족 시 LRU가 락 키를 지우면 방어선 하나가 조용히 사라지므로, 키를 버리는 대신 쓰기를 실패시킵니다. 실패해도 DB 유니크 제약이 최종 방어선으로 남습니다.

**알려진 한계**
- 스키마는 `ddl-auto: update` 사용. 운영 환경에서는 Flyway 등 마이그레이션 도구로 관리 필요
- RDS 기본 collation(`utf8mb4_0900_ai_ci`)과 로컬(`utf8mb4_unicode_ci`)이 다름
- 수동 배포(scp + systemd). CI/CD 미구성

## 실무와의 연결

실무(P2P 금융 플랫폼)에서는 이름 없는 `@Async`가 기본 실행기로 떨어져 여러 상품의 상환이 동시에 돌면서 공용 계좌 잔액이 덮어써지는 문제가 있었습니다.
그때는 처리량보다 정합성을 우선해 **단일 스레드 실행기로 순차화**하고, 외부 금융기관 잔액과 내부 원장을 대사해 누락을 자동 보정했습니다.
이 프로젝트에서는 같은 문제를 순차화가 아닌 **DB 수준의 동시성 제어**로 풀면 어떤 선택지가 있는지 비교했습니다.

## 실행

```bash
docker compose up -d      # MySQL 8 (3307), Redis 7 (6380)
./gradlew test
./gradlew bootRun         # 헬스체크: curl localhost:8080/actuator/health
```

## 다음 작업

- 완료: AWS 배포 (EC2 + RDS, 보안 그룹 체이닝, systemd 운영)
- 진행 중: Outbox + Kafka 이벤트
- 다음: Saga 보상 → 정산 배치·대사

학습노트: [backend-notes](https://github.com/dev-minhoo/backend-notes)