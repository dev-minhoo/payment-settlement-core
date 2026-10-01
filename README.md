개인 프로젝트(Spring Boot·JPA·Redis·Docker)에서 결제 중복 방지를 DB 조회 → Redis 선점(SET NX + Lua 원자적 해제) → DB 유니크 제약의 3중 방어로 구현하고, 동시 요청 50건에서 결제 1건·외부 이체 1회를 JUnit 동시성 테스트로 검증했습니다.
