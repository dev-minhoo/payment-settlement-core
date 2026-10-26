# payment-settlement-core

돈이 오가는 시스템에서 **"두 번 나가지 않고, 사라지지 않게"** 를 직접 구현하고 테스트로 증명하는 개인 프로젝트입니다.
P2P 금융 플랫폼 실무에서 겪은 정합성 문제(중복 이체, 공용 계좌 잔액 덮어쓰기)를 실무 코드 없이 문제만 가져와 새로 만들었습니다.

`Java 17` `Kotlin` `Spring Boot` `Spring Batch` `JPA` `MySQL 8` `Redis 7` `Kafka` `Docker` `JUnit 5` `AWS EC2` `AWS RDS`

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

## 4. 결제 이벤트 전달: Transactional Outbox + 멱등 소비자

결제가 완료되면 정산 쪽에 알려야 합니다. 그런데 "DB에 결제 저장"과 "Kafka에 이벤트 발행"은 서로 다른 시스템이라 한 트랜잭션으로 묶을 수 없습니다(이중 쓰기 문제).
- 저장 후 발행 직전에 죽으면 → 결제는 됐는데 정산은 모름 (이벤트 유실)
- 발행 후 DB 커밋이 실패하면 → 없는 결제를 정산 (유령 이벤트)

**구조**

```
pay() ──(트랜잭션 없음: 외부 이체 호출)
  └ PaymentRecorder.recordCompleted()  [@Transactional]
       ├ payment INSERT (COMPLETED)
       └ outbox_event INSERT (PENDING)        ← 둘 다 커밋되거나 둘 다 롤백

OutboxRelay (1초 간격 폴링)
  PENDING 조회 → Kafka 발행(acks=all, ack 대기) → SENT
  실패 시 PENDING 유지 + attempt_count·last_error 기록 → 다음 실행에서 재시도

PaymentEventConsumer → SettlementEventHandler  [@Transactional]
  ├ processed_event INSERT (event_id 유니크)  ← 이미 있으면 건너뜀
  └ settlement_entry INSERT                   ← 둘 다 커밋되거나 둘 다 롤백
```

| 설계 | 이유 |
|---|---|
| `pay()` 전체가 아니라 `PaymentRecorder`에만 트랜잭션 | 외부 이체 호출 동안 DB 커넥션을 잡지 않기 위해. 별도 빈으로 둔 것은 self-invocation 시 `@Transactional`이 적용되지 않기 때문 |
| `OutboxAppender`는 `Propagation.MANDATORY` | 열린 트랜잭션 없이 이벤트만 단독 커밋되는 실수를 구조적으로 차단 |
| Kafka key = 결제 ID | 같은 결제의 이벤트는 같은 파티션 → 순서 유지 |
| 헤더 `eventId` | 전달은 at-least-once(발행 성공 후 SENT 저장 전에 죽으면 재발행). 소비자가 eventId로 중복 제거 |
| 발행 실패 시 배치 중단 | 브로커 장애면 뒤 이벤트도 실패할 가능성이 크고, 순서를 지키기 위해 |

**검증** (`PaymentOutboxTest`, `PaymentOutboxAtomicityTest`, `OutboxRelayTest`, `PaymentEventConsumerTest`, 실제 Kafka 브로커 사용)
- 결제 1건 → 이벤트 1건, 같은 키 재요청·동시 50건에도 이벤트 1건
- 아웃박스 기록이 실패하면 결제도 롤백 (유실 상태가 생기지 않음)
- 트랜잭션 밖에서 아웃박스 단독 기록 시도 → 거부
- 발행 실패 → PENDING 유지(attempt 1) → 재실행 시 SENT(attempt 2) → 정산 1건
- 같은 이벤트를 Kafka로 두 번 발행 → 정산 1건
- 같은 이벤트를 동시에 20번 소비 → 처리 1건

**한계와 다음 단계**
- 발행기를 여러 대 띄우면 같은 PENDING을 동시에 집을 수 있음(결과는 소비자 멱등으로 흡수). 다중 인스턴스라면 `FOR UPDATE SKIP LOCKED`나 리더 선출
- 폴링 대신 CDC(Debezium)로 binlog를 읽는 방식은 지연이 짧고 DB 조회 부하가 없지만 운영 구성 요소가 늘어남
- 처리 불가능한 메시지(DLQ), SENT 이벤트 정리 배치는 미구현
- AWS 배포본은 Kafka 도입 전 버전. 다시 배포하려면 브로커와 `KAFKA_BOOTSTRAP_SERVERS` 필요

## 5. 정산 대사 배치: Kotlin + Spring Batch

이벤트를 멱등하게 처리해도, 운영에서는 "정말 맞는지"를 주기적으로 확인하는 대사가 마지막 안전망입니다.
대상 날짜에 완료된 결제와 정산 원장을 맞춰 보고, 불일치를 유형별로 기록합니다. (`reconciliation` 패키지, Kotlin)

```
paymentReconciliationJob (JobParameters: targetDate)
  Step 1 clearPreviousResultStep (tasklet)  같은 날짜의 이전 대사 결과 삭제 → 해소된 불일치가 남지 않게
  Step 2 reconcileStep (chunk)
    reader    결제별 정산 건수·합계 집계 (payment LEFT JOIN settlement_entry, 커서)
    processor 판정 — 일치면 null로 걸러냄
    writer    reconciliation_mismatch upsert ((target_date, payment_id) 유니크)
```

| 불일치 유형 | 판정 | 후속 조치 |
|---|---|---|
| `MISSING_SETTLEMENT` | 완료 결제인데 정산 0건 | `RETRY_EVENT` — 이벤트 재처리로 메울 수 있음 |
| `DUPLICATE_SETTLEMENT` | 정산 2건 이상 | `MANUAL_REVIEW` — 이중 정산 가능성, 사람이 판단 |
| `AMOUNT_MISMATCH` | 정산 합계 ≠ 결제 금액 | `MANUAL_REVIEW` |

| 설계 | 이유 |
|---|---|
| 판정 규칙을 `ReconciliationClassifier`(순수 함수)로 분리 | 스프링·DB 없이 규칙만 단위 테스트. 배치 설정과 업무 규칙을 분리 |
| 금액 비교는 `compareTo` | `BigDecimal`의 `equals`(Kotlin `==`)는 스케일까지 비교 → `10000` ≠ `10000.00` |
| 결과 테이블 유니크 + upsert | 같은 날짜 재실행·청크 재시작에도 결과 1회. MySQL은 같은 값 upsert 시 영향 행 0 → `assertUpdates(false)` |
| JDBC Job Repository (`spring-boot-starter-batch-jdbc`) | 실행 이력·리더 위치를 `BATCH_` 테이블에 저장 → 실패 시 마지막 커밋 청크부터 재시작 |
| Kotlin은 JDBC로 직접 조회 | Kotlin 컴파일러는 Lombok이 만든 Java getter를 보지 못함. 기존 Java 코드와는 테이블로만 연결 |
| 기동 시 자동 실행 끔 (`spring.batch.job.enabled=false`) | 실행 시점은 스케줄러·운영자가 `targetDate`를 지정해 명시적으로 |

**검증** (`ReconciliationClassifierTest`, `ReconciliationJobTest`, 실제 MySQL)
- 정상·누락·중복·금액 불일치·스케일만 다른 금액·대상 외(다른 날짜, 미완료) 결제를 섞어 넣으면 불일치 3건만 기록
- 청크 크기 2로 실행: 읽기 5 · 걸러냄 2 · 쓰기 3, 커밋 3회 이상
- 같은 날짜 2회 실행 → 결과 3건 유지 (멱등)
- 누락을 메운 뒤 재실행 → 해소된 건은 결과에서 사라짐

**한계와 다음 단계**
- 정산 원장에만 있고 결제가 없는 건(고아 정산)은 아직 판정하지 않음
- `RETRY_EVENT` 자동 재처리(해당 결제의 아웃박스 이벤트 재발행)는 미구현 — 현재는 기록까지
- 커서 리더는 MySQL에서 결과를 한 번에 받음. 대량이면 페이징 리더 또는 결제 ID 범위 파티셔닝

## 실무와의 연결

실무(P2P 금융 플랫폼)에서는 이름 없는 `@Async`가 기본 실행기로 떨어져 여러 상품의 상환이 동시에 돌면서 공용 계좌 잔액이 덮어써지는 문제가 있었습니다.
그때는 처리량보다 정합성을 우선해 **단일 스레드 실행기로 순차화**하고, 외부 금융기관 잔액과 내부 원장을 대사해 누락을 자동 보정했습니다.
이 프로젝트에서는 같은 문제를 순차화가 아닌 **DB 수준의 동시성 제어**로 풀면 어떤 선택지가 있는지 비교했습니다.

## 실행

```bash
docker compose up -d      # MySQL 8 (3307), Redis 7 (6380), Kafka (9092)
./gradlew test
./gradlew bootRun         # 헬스체크: curl localhost:8080/actuator/health
```

## 다음 작업

- 완료: AWS 배포 (EC2 + RDS, 보안 그룹 체이닝, systemd 운영)
- 완료: Outbox + Kafka 이벤트, 멱등 소비자
- 완료: 정산 대사 배치 (Kotlin + Spring Batch)
- 진행 중: GitHub Actions CI
- 다음: 대사 결과 자동 재처리, Saga 보상

학습노트: [backend-notes](https://github.com/dev-minhoo/backend-notes)