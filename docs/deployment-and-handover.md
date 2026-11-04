# Phase 7 — Deployment, backup/restore và bàn giao

## Phạm vi bằng chứng

Kiểm thử User chạy trên PostgreSQL/Kafka/Mailpit biệt lập; smoke image dùng dữ liệu giả và Docker resources tên ngẫu nhiên. Không dùng DB/SMTP thật hoặc sửa repo service khác. Payment listener giữ tắt đến khi producer/fixture đảo thưởng version2 được bàn giao. Kết quả local không thay thế nghiệm thu môi trường demo/production và các owner.

Xem [ma trận requirement](verification-matrix.md), [20 API examples](api-examples.http), [fixtures messaging](fixtures/messaging-contract.md) và [reputation recovery](reputation-contract-and-recovery.md). HTTP examples cần private client environment; không lưu token/password/OTP/service key trong file được commit.

## Build và kiểm chứng image

JDK21, Docker Desktop và Maven Wrapper được dùng ở Windows. Chạy từ workspace:

```powershell
.\mvnw.cmd -B verify
docker build --tag user-service:phase7-local .
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Smoke-Deployment.ps1
```

Nếu dùng Maven local cache tùy chọn, truyền `-Dmaven.repo.local=<private-cache>`; test temp cũng có thể đặt trong workspace qua `-DargLine=-Djava.io.tmpdir=<absolute-private-temp>`. Không bỏ integration test khi nghiệm thu. Smoke script yêu cầu Java source launcher và System.Drawing trên Windows; không phải smoke script Linux.

Dockerfile dùng Maven3.9.11/JDK21 build app và JRE21 Alpine runtime, chạy user không root. BuildKit cache Maven chỉ lưu dependency trong build cache, không đưa credentials/local .env vào image. `.dockerignore` allowlist chỉ gửi pom/src/Dockerfile. Image package bỏ test trong build; bộ `verify` là gate độc lập trước khi phát hành.

`Smoke-Deployment.ps1 -Image <built-image>` tạo network, hai DB volume và hai media volume riêng, host port ngẫu nhiên chỉ loopback. Script không đọc workspace .env; tự tạo RSA/OTP/caller keys và hai Admin fixture trong thư mục ignored `.tools/deployment-<uuid>`. Script kiểm tra API/SMTP, image restart, DB outage, backup/restore vào DB/media mới và thu hồi refresh; finally chỉ xóa Docker resources do chính lần chạy tạo. Report không chứa secret; env/key/dump/archive/log là dữ liệu private, cần quản lý theo retention nội bộ và không upload toàn thư mục làm artifact công khai.

## Cấu hình môi trường đích

`compose.yml` là môi trường **local**: DB/Kafka/SMTP port loopback, Kafka single broker PLAINTEXT, Mailpit không auth/TLS. Không dùng nguyên cấu hình này làm cấu hình production. Môi trường đích chạy image với env/secret mounts do hạ tầng cấp, không bật profile `local` và không bake secret vào image.

| Nhóm | Cấu hình cần bàn giao | Kiểm tra trước bật |
| --- | --- | --- |
| DB | DB_URL/DB_USERNAME/DB_PASSWORD; PostgreSQL17, user_db riêng | Primary DB, Flyway V1–V4, quyền migration/runtime, backup và hạn mức kết nối |
| RSA/OTP | JWT_PRIVATE_KEY_PATH/JWT_PUBLIC_KEY_PATH/JWT_KEY_ID, OTP_HMAC_SECRET | PEM matching >=2048bit, private mount read-only, secret ổn định qua restart |
| Rotation | JWT_PREVIOUS_KEY_ID/JWT_PREVIOUS_PUBLIC_KEY_PATH | Khóa cũ có mặt trong overlap ít nhất16phút; gateway/backend cũng chấp nhận cả hai kid |
| Media | MEDIA_STORAGE_DIR persistent volume ngoài webroot | UID/GID image có quyền ghi, dung lượng/backup, không public static serve |
| Caller keys | Sáu INTERNAL_*_KEY độc lập | Blank=tắt caller; chỉ cấp đúng owner; không dùng bearer hoặc key chung |
| SMTP | MAIL_HOST/PORT/USERNAME/PASSWORD/FROM, MAIL_SMTP_AUTH=true, MAIL_STARTTLS=true, SUPPORT_EMAIL | Sender/support thật đã verify; kiểm OTP và mail khóa/mở đến mailbox kiểm thử được cho phép |
| Admin | ADMIN_SEED_ENABLED, hai bộ ADMIN_1_*/ADMIN_2_* | Hai identity độc lập; nhận và verify OTP, login ADMIN; không dùng tài khoản fixture làm demo identity thật |
| Kafka | KAFKA_BOOTSTRAP_SERVERS, security config Spring Kafka phù hợp broker | TLS/SASL/ACL, partition/key accountId, retention/lag, provisioning topics do broker owner |
| Workers | OUTBOX_KAFKA_ENABLED/OUTBOX_EMAIL_ENABLED | Bật độc lập sau khi broker/SMTP sẵn sàng; bootstrap opt-in một lần |
| Reputation | REPUTATION_CONSUMER_ENABLED=false cho đến bàn giao | Payment sửa reversal thưởng thành version2 và cung cấp fixture producer mới; validate order/recovery |
| Network | SERVER_PORT=8081, domain/origin/routing | Chỉ Gateway public; chặn public /internal/**; không expose DB/media disk; probes được hạ tầng truy cập |

User hiện không có browser CORS allowlist; cấu hình cùng origin tại Gateway hoặc chốt CORS ở Gateway. User không tin forwarded headers (`forward-headers-strategy=none`). Tổng timeout access-state1s do caller triển khai; User có DB connection timeout500ms/query timeout1s nhưng không tự chứng minh ngân sách end-to-end của Gateway/WS/backend.

Health: `/actuator/health/liveness` phản ánh process; `/actuator/health/readiness` có DB và trả503 khi DB không sẵn sàng. Kafka/SMTP worker lỗi giữ outbox pending; readiness hiện không khẳng định delivery khỏe. Theo dõi thêm backlog/oldest age/attempts bằng `scripts/Outbox-Status.sql`, consumer lag/DLT và storage capacity. Không bật DEBUG mail/Kafka/domain trên môi trường thật vì có thể lộ input invalid hoặc thông tin hạ tầng.

## Backup nhất quán DB và media

Quy trình maintenance đơn giản được smoke kiểm chứng, không claim hot backup/PITR. Cần chốt lịch/RPO/RTO, mã hóa backup và nơi lưu riêng với owner vận hành.

1. Tắt traffic ghi, dừng **tất cả** User instance/consumer/bootstrap/outbox/email/media cleanup worker; xác nhận không còn writer. Không chỉ dừng một replica rồi lấy snapshot.
2. Với DB ổn định, chạy pg_dump custom format bằng client cùng major PostgreSQL17. Dùng secret injection/pgpass của môi trường, không đặt password trong câu lệnh hoặc log. Lưu dump và media archive trong cùng backup set có timestamp, image digest, schema version/checksum, hash các archive. Dùng tar/snapshot giữ ownership/permissions của volume media; không chỉ sao chép metadata DB.
3. Ghi tham chiếu tới bản secret RSA current/previous/OTP và caller config trong secret store. Không đưa plaintext secret vào manifest hoặc log. Cần khôi phục cùng bộ key/OTP secret để giữ semantics token/OTP; mất private key cần quy trình rotation riêng.
4. Xác minh dump có thể đọc (`pg_restore --list`), kiểm hash và media archive; tiếp tục writers/traffic sau khi bộ backup hoàn tất. Đo thời gian dừng thật trên môi trường đích.

Smoke script sử dụng `pg_dump -Fc`, archive media khi app đã dừng, và `pg_restore --no-owner --exit-on-error` vào DB mới. Không chạy `TRUNCATE`, `DROP`, `pg_restore --clean` hoặc `docker compose down -v` trên DB/volume vận hành.

## Restore và recovery

1. Tạo DB và media volume **mới, trống** trong môi trường cách ly; giữ instance cũ để có đường quay lại. Không ghi đè DB/media đang phục vụ. Dừng mọi worker đến khi đối soát xong.
2. Kiểm hash backup set; restore DB bằng pg_restore fail-fast và media với owner/permissions tương ứng image. Mount đúng RSA/OTP secret/config của backup. Khởi động đúng image digest/schema trước, không tự rollback migration hoặc sửa Flyway checksum.
3. Giữ Kafka/email/reputation/bootstrap tắt. Kiểm schema, account/status/admin_lock/reputation_lock, hai Admin verified, avatar URL/hash/owner access, revoked refresh, access-state và probes. Kiểm JWT cũ chỉ còn được dùng nếu vẫn hợp lệ theo thời gian và state hiện tại.
4. Đối soát Kafka source offsets và outbox marker với Payment/Trading/Notification. DB restore quay về một thời điểm cũ có thể khiến event/email được gửi lại hoặc source offset nằm sau receipt/history; **không tự reset offset hoặc bật consumer**. Payment cung cấp replay/correction được duyệt, consumer owner bảo đảm dedup/version. SMTP có thể gửi trùng, cần quyết định vận hành riêng. Không rewrite score/history/receipt để khớp offset.
5. Chạy các smoke bắt buộc trên URL môi trường đích, ghi thời gian restore và kết quả, rồi mới mở workers/traffic theo owner phê duyệt. Nếu dữ liệu không nhất quán, giữ môi trường mới cách ly; không chuyển traffic sang nó.

## Nghiệm thu liên service còn cần owner

| Owner | Bằng chứng cần ghi trước nghiệm thu | Trạng thái |
| --- | --- | --- |
| Payment | Producer/fixture thực tế penalty/reward/reversal; mọi reversal version2; originalDecisionId trực tiếp; key/reference/recipient/order; thử retry/recovery | Chưa bàn giao fixture mới; listener tắt |
| Trading | USER_PROFILE_UPDATED snapshot/tombstone + bootstrap; chỉ nhận version lớn hơn; không lộ contacts/score; replay cũ không làm hiện profile | Có fixture consumer trong User test; chưa owner thật |
| Notification | ACCOUNT_LOCKED/UNLOCKED, REPUTATION_CHANGED whitelist/recipient/reference/actual delta; dedup eventId | Có producer/fixture trong User; chưa owner thật |
| Gateway | Chặn public /internal/**, key riêng và timeout tổng1s, JWT kid/claims/ACTIVE; oldJWT sau lock | User kiểm nội bộ, chưa Gateway thật |
| Farming/Trading/Chat/Payment/Notification backend | Fail closed khi access-state unavailable; lock có hiệu lực với request mới, cache/reconnect/WS theo BA | Chưa liên service; User không kiểm chứng WS của Chat |
| Vận hành/demo | URL/domain/origin, TLS, real SMTP/support, hai Admin thật đã verify, secret rotation overlap, backup restore rehearsal và RPO/RTO | Smoke local không thay thế môi trường đích |

Không gửi message cho owner tự động hoặc sửa repo khác. Người bàn giao ghi URL/version image/commit/eventId test/kết quả nhận ở consumer vào biên bản riêng, không ghi secret/payload private. Chỉ đánh dấu Phase7 hoàn thành toàn bộ khi những dòng còn mở có bằng chứng thực tế.
