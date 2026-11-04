# Phase 7 — Bằng chứng image/backup restore local

UTC: 2026-11-02T15:40:16.0730241Z
Image: user-service:phase7-local
Image ID: sha256:0d521b2136a7855dd970f15e752075bfa0b0d046530dddfd66407dc40cca7dcd

| Check | Result |
| --- | --- |
| image runs as non-root | PASS |
| image liveness/readiness UP | PASS |
| Flyway V1-V4 applied on fresh database | PASS |
| seed Admin one verified via isolated SMTP | PASS |
| seed Admin one login | PASS |
| seed Admin two verified via isolated SMTP | PASS |
| seed Admin two login | PASS |
| register sends OTP through container SMTP | PASS |
| Farmer verifies email | PASS |
| Farmer login with image RSA key pair | PASS |
| image accepts real multipart PNG avatar | PASS |
| internal access-state requires service key | PASS |
| independent Payment key reads access-state | PASS |
| service key cannot replace user bearer token | PASS |
| Admin locks Farmer in image | PASS |
| old JWT blocked after committed Admin lock | PASS |
| locked account cannot refresh | PASS |
| Gateway key sees committed lock with no-store | PASS |
| second verified Admin unlocks Farmer | PASS |
| unlock does not restore revoked refresh token | PASS |
| avatar bytes persist across image container restart | PASS |
| profile avatar metadata persists across restart | PASS |
| database outage fails access-state closed | PASS |
| database outage makes image readiness DOWN | PASS |
| paired PostgreSQL/media backup after quiescing writers | PASS |
| restored DB/media preserve avatar identity and exact bytes | PASS |
| restored profile references restored media | PASS |
| restore preserves two verified Admins without duplicate seeding | PASS |
| restore preserves revoked refresh token state | PASS |
| restored access-state uses preserved service key | PASS |

Only synthetic fixture identities and isolated Mailpit were used. This does not verify real Gateway, Payment, Trading, Notification, external SMTP or production deployment.
Reputation listener remained disabled. Bản gốc report nằm ở .tools/deployment-37285583f92142c78c713c8b14a53c02/report.md. Env/key/dump/media archive/log chỉ nằm trong thư mục ignored này; không commit hoặc upload chúng. Log tổng: .tools/phase7-deployment-smoke.log; log build: .tools/phase7-docker-build.log.

Kết quả: 30/30 kiểm tra đạt, exit0. Docker resources của smoke đã được cleanup. Source baseline Phase6 commit eefe7a2, 168 test verify đạt; Dockerfile Phase7 bỏ go-offline và dùng Maven cache để build từ source. Bằng chứng này chỉ là User/local, không phải biên bản nghiệm thu production/liên service.
