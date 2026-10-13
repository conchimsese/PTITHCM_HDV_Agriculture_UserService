# Quyết định triển khai giai đoạn nền

Ngày: 08/10/2026. Chỉ sửa repo User; không commit/push. Yêu cầu mới nhất giới hạn bootstrap, DB/migration, errors, internal access-state, local config và kiểm thử.

## Nguồn và phiên bản

- Đã đọc văn bản/bảng từ `BA - SOA.docx`: User §1–19 và Contract chung MVP v2.1 §1–19, D01–D07, Change Log. Không suy nội dung riêng trong ảnh/sơ đồ nhúng.
- Ưu tiên: chỉ đạo mới nhất → Conflict đã chốt → Contract v2.1 → rule → spec → prompt triển khai.
- Repo ban đầu chỉ có hai tài liệu. Java 21, Boot 3.5.16 và Maven 3.9.11. Boot patch được xác nhận trong tài liệu chính thức và Maven Central; dependency runtime/test theo BOM, không pin riêng.
- Tài liệu chính thức: [system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [managed dependencies](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), [Maven 3.9.11](https://maven.apache.org/docs/3.9.11/release-notes.html).

## Đối chiếu schema BA cũ/v2.1

| Nội dung cũ | Baseline áp dụng | Thực hiện |
| --- | --- | --- |
| User §4 nói outbox cần duyệt; các mục cũ nhấn mạnh 5 bảng | User §5.7, Contract §10/§18 yêu cầu history/outbox/media | Migration tạo 5 bảng nền + 3 bảng v2.1 |
| Khóa Admin và reputation có thể bị gộp | Contract §10 tách admin_lock/reputation_lock | Hai flag độc lập, ACTIVE chỉ khi không có lock và đã verify |
| Public profile ví dụ cũ/điểm chưa duyệt | Contract §4/§9 public whitelist | Access-state chỉ 4 field; chưa có public profile API |
| Mở khóa chỉ cần email verified | User §8.2/Contract §12: Farmer/Trader score>0 và không còn lock khác | CHECK ACTIVE đúng điều kiện; ADMIN score null |

`otp_issuances` là bảng kỹ thuật lưu timestamp phát OTP riêng với OTP có thể cleanup, đáp ứng Contract §19. FK account và index email/time; không xóa cascade khi cleanup OTP. Giai đoạn auth sau sẽ khóa row account, ghi OTP + issuance cùng transaction và kiểm quota; bảng này chưa chứng minh quota API đã được triển khai.

Profile có cột kỹ thuật `account_role` cố định, composite FK `(profile_id,account_role)` → `(accounts.account_id,role)` để DB chặn tạo sai profile theo role; không thêm role mới. Transaction register giai đoạn sau phải bảo đảm tạo đúng profile. DB chưa ép account FARMER/TRADER luôn có profile vì cần insert account rồi profile trong cùng transaction.

Score Farmer/Trader phải được ghi là 10 khi register (chưa có register ở giai đoạn này); ADMIN null. CHECK 0..10, bước 0,5, score0 yêu cầu reputation_lock; ACTIVE yêu cầu verified và các flag cho phép. Mọi FK nằm user_db; counterparty/contract/decision UUID chỉ là logical reference. Index reward/reversal chống trùng là schema nền, chưa có consumer/reward policy.

OTP/refresh lưu hash hex64 (HMAC-SHA-256/SHA-256), thêm invalidated_at để phân biệt vô hiệu mã cũ với used_at chỉ verify thành công. Khi phát mã mới cần invalidation mã hết hạn/cũ trước insert vì partial unique index chỉ cho một mã chưa dùng/chưa invalidated.

Outbox có channel KAFKA/EMAIL, retry metadata và index bản ghi chưa publish. Đây là schema phục hồi cho relay sau; không publish-after-save, chưa claim delivery semantics đã hoạt động. Business Kafka payload sau không được có email/OTP/token; payload email nằm riêng channel, không đưa ra Kafka. `media_assets` có metadata/UUID storage key và giới hạn MIME/size; chưa có filesystem adapter hoặc avatar API.

## Access-state và security

- Endpoint contract duy nhất: GET `/internal/v1/accounts/{accountId}/access-state`. Credential riêng từng caller qua `X-Service-Key`, không có role SERVICE trong accounts. Allowlist cố định endpoint/method này cho mỗi caller có key cấu hình; blank vô hiệu caller, key ngắn/trùng bị từ chối startup.
- Key so sánh constant-time; không lưu credentials trong Authentication, không tạo session, không tin identity header từ client. Service key không authorizes public API.
- Repository JDBC truy vấn DB primary mỗi request; không HTTP tự gọi User, không cache ACTIVE. Dùng JDBC để chỉ chọn account_id/status/role, không expose entity; JPA đã cấu hình `validate` cho entity của các giai đoạn sau.
- 200 với status hiện tại và boolean; caller nội bộ phải từ chối request người dùng khi boolean false, timeout hoặc User lỗi. 404 account absent, 503 DB unavailable, no-store cả success và error. Tổng timeout 1 giây do caller đặt; timeout query/pool của server không thay thế timeout tổng caller.
- Public API deny by default; chưa claim JWT validation/cấp token đã hoàn tất. Actuator chỉ expose health, show-details never. Không cấu hình discovery cho issuer URN; JWT wiring thuộc giai đoạn auth sau.
- CORS public chưa mở. Compose bind localhost; deployment private networking và chặn route internal thuộc Gateway/deployment owner, không sửa repo khác.

## Phạm vi chưa làm

Auth/OTP SMTP/seed Admin, rate limits, refresh rotation, profile/Admin CRUD, media và outbox relay còn ở giai đoạn sau. Reputation consumer, quy tắc sửa quyết định/version/retry riêng User và avatar Admin chưa triển khai theo chỉ đạo hiện tại. Chưa cần quyết định các nhánh đó để hoàn thành giai đoạn nền.

## Verification thực tế

- `mvnw.cmd -version`: Maven 3.9.11, JDK Temurin 21.0.12.1. Wrapper tải ZIP với SHA-256 đã pin; ZIP bootstrap cũng đối chiếu SHA-512 từ Maven Central.
- `mvnw.cmd -B -Dmaven.repo.local=<workspace>/.m2 -Ddebug=false verify`: **BUILD SUCCESS**, 15 test Web/security + 8 integration test PostgreSQL 17.11 qua Testcontainers; tổng 23, không failure/error/skipped. Flyway validate/migrate/re-run, UNIQUE/role/profile/score, row lock, quota retention, state sau commit và health đều đạt. Log local ở `.tools/verify.log`, report ở `target/`.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Smoke-Local.ps1`: **SMOKE PASS**, JAR thật port 8081 với profile local; health UP, thiếu key401, account404, ACTIVE200, LOCKED sau commit200/accessAllowed=false, no-store, DB thật dừng→503 ACCESS_STATE_UNAVAILABLE/readiness DOWN. JVM và Compose project/volume test đã được dọn.
- Docker Desktop lúc đầu chưa chạy; đã khởi động để chạy PostgreSQL thật. Không có test bị bỏ qua vì Docker/network. Dockerfile đã tạo, chưa build/run image ứng dụng; smoke chạy JAR trên host và PostgreSQL trong Compose.
- Môi trường local bình thường cần điền DB_PASSWORD và key riêng cho caller muốn bật trong `.env`. Không tạo credential production, không seed Admin trong lượt này. Không commit/push.

## Sửa cấu hình kết nối local — 12/10/2026

- PostgreSQL Windows đang phục vụ localhost:5432. Kiểm tra trực tiếp xác nhận database `user_db` tồn tại, role `user_db` không tồn tại; mật khẩu người dùng cấu hình đăng nhập thành công với role `postgres`.
- Tạo `.env` local bị Git ignore với DB_USERNAME=postgres và credential hiện có. Khôi phục datasource/port trong application.yml về biến cấu hình; xóa credential khỏi `.env.example`, sửa DB_PORT mẫu thành 5432. Không tạo/đổi role, password hoặc xóa dữ liệu PostgreSQL.
- `mvnw.cmd package`: BUILD SUCCESS, 15 test Web/security đạt. Chạy JAR với profile local trên port8081: Flyway validate thành công, schema v1 đã cập nhật nên không chạy migration mới, `/actuator/health` trả UP. Đã dừng phiên JAR kiểm tra; chạy lại theo README khi cần. Không chạy lại Testcontainers IT trong lần sửa cấu hình này.
