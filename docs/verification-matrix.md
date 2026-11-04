# Ma trận requirement → code → kiểm chứng

Baseline Phase6: `clean verify` đạt168 test (43 unit/Web/security +125 integration), không failure/error/skipped; báo cáo local `.tools/phase6-complete-verify.log`. Test PostgreSQL/Kafka/Mailpit và JAR restart dùng môi trường biệt lập. Phase7 build image và smoke deployment/backup restore **30/30 check đạt**; bằng chứng trong [phase7-local-verification.md](phase7-local-verification.md). Không đổi Java business code hoặc migration.

| Requirement | Code chính (src/main/java/vn/agriculture/soa/user/) | Test/bằng chứng | Phạm vi còn thiếu |
| --- | --- | --- | --- |
| Nền Java21/Boot/PostgreSQL/Flyway | UserServiceApplication, db/migration V1–V4 | FoundationPostgresIT, image smoke | Vận hành DB đích |
| JSON lỗi thống nhất, không lộ SQL/secret | error/, security/ | AccessStateWebTest, FoundationPostgresIT | Gateway error propagation |
| Register F/T, OTP/quota/resend, verified | auth/AuthService, Otp*, AuthMaintenance | AuthPostgresIT, image SMTP+hai Admin | SMTP/mailbox thật |
| Login rate limit, refresh rotation/logout/concurrency | auth/, V2 | AuthPostgresIT | Chính sách vận hành/rate ở Gateway |
| JWT RS256/kid/claims/rotation | auth/JwtTokens | JwtTokensTest, AuthPostgresIT | Rotation trên các owner cùng overlap16phút |
| Internal access-state primary/fail closed/no-store | account/AccessState*, security/ServiceKeyFilter | AccessStateWebTest, FoundationPostgresIT, image DB outage | Caller timeout tổng1s, public routing và WS |
| Private/public profile, DTO whitelist, role/owner | profile/ | ProfilePostgresIT | Trading replica thật |
| Admin sáu endpoint, allowlist/pagination/status/soft-delete | admin/ | AdminAccountWebTest, AdminPostgresIT | Hai Admin demo thật |
| Self/lastAdmin/concurrent protection, lock/refresh/JWT | admin/AdminAccountService, security/CurrentAccountFilter | AdminPostgresIT, AuthPostgresIT, image lock/JWT | Gateway/backend/WS thật |
| JPEG/PNG/WebP/size/pixel/path/storage/access | media/ | AvatarImagesTest, MediaStorageTest, AvatarPostgresIT | Dung lượng và quyền volume đích |
| Avatar bền vững; metadata/profile/outbox atomic | media/, profile/ | AvatarPostgresIT JAR restart; image restart+backup restore | Backup schedule/RPO/RTO đích |
| Outbox ACK/retry/order/multi-worker, SMTP riêng | outbox/, V3 | OutboxPostgresKafkaIT, SmtpDeliveryIT, AccountStatusMailerTest | Kafka ACL/TLS/retention, SMTP thật |
| Profile bootstrap/version/tombstone | outbox/ProfileBootstrap | OutboxPostgresKafkaIT, fixtures/ProfileReplica | Trading áp dụng event thực tế |
| Payment validator/policy F/T/version | reputation/ReputationDecision, ReputationPolicy | ReputationValidationTest (7) + docs fixtures | Fixture mới producer reversal thưởng2 |
| Penalty/reward/cap/dedup/history | reputation/ReputationService, V4 | ReputationPostgresIT (23) | Decision/order được Payment bàn giao |
| Reversal actualDelta/clamp/one per original/manual unlock | reputation/ReputationService | ReputationPostgresIT; actual0/clamp0–10/race/rollback/Admin/JWT | Payment producer mới, không hỗ trợ reversal-of-reversal |
| Consumer commit-before-ACK/retry/DLT/replay | reputation/ReputationConsumer, *KafkaConfiguration, *DeadLetter*, *Replay | ReputationKafkaIT (5), ReputationPostgresIT replay | Source lag/retention/ACL/recovery liên service |
| REPUTATION_CHANGED/account/profile whitelist | reputation/ReputationOutboxWriter, outbox/*Writer | ReputationPostgresIT, AdminPostgresIT, OutboxPostgresKafkaIT | Notification nhận và dedup thật |
| 20 endpoint đúng BA | auth/profile/admin/media/account controllers | Web/domain tests; docs/api-examples.http đủ20 request | End-to-end qua Gateway |
| Image non-root/probes/keys/migration/persistence | Dockerfile, scripts/Smoke-Deployment.ps1 | Docker build + deployment smoke | Image registry/digest và rollout đích |
| Backup paired DB/media, restore giữ quyền/state | scripts/Smoke-Deployment.ps1, deployment-and-handover.md | Restore sang DB/media volume mới và kiểm API/bytes/refresh/Admin | Production rehearsal/secret store/cross-owner replay |

Consumer fixture không được coi là owner service thật. Không ghi `PASS` cho tích hợp còn thiếu chỉ vì unit/integration User đạt; xem bảng owner trong deployment-and-handover.md.
