# User Service — giai đoạn nền

Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.11, PostgreSQL 17, Flyway; port mặc định **8081**. Nguồn yêu cầu: `docs/BA - SOA.docx` (User Service và Contract chung MVP v2.1) và `docs/Prompt_User_Service.md`.

## Phần đã triển khai

- Project và dependency theo Spring Boot BOM: Web, Validation, Security/OAuth2 Resource Server, JPA, PostgreSQL, Flyway, Mail, Kafka, Actuator, test.
- Migration `V1__user_foundation.sql`: năm bảng nền, `reputation_history`, `outbox`, `media_assets` và `otp_issuances` cho quota độc lập cleanup. UUID, TIMESTAMPTZ, FK nội bộ user_db, ràng buộc role/status/score/profile và index.
- `GET /internal/v1/accounts/{accountId}/access-state`: đọc DB primary trực tiếp mỗi lần, không cache, service key riêng từng caller, endpoint allowlist, response/error `Cache-Control: no-store`.
- Lỗi JSON `{timestamp,status,code,message,path}`; không trả SQL, stack trace hoặc secret. Health chỉ trả trạng thái tổng quát.
- Cấu hình local, Dockerfile và Compose riêng User.

Giai đoạn này chưa triển khai register/login/JWT issuing và validation cho public API, OTP/SMTP/seed, CRUD profile/Admin, upload avatar, Kafka relay hoặc reputation consumer. Các đường public API bị chặn mặc định. OAuth2/JWT dependency đã có nhưng chưa coi authentication đầy đủ là hoàn tất. Các bảng history/outbox/media chỉ là nền schema; chưa có xử lý nghiệp vụ tương ứng.

## Chạy local trên máy

Yêu cầu JDK 21 (`java -version`, `javac -version`), Docker Desktop đang chạy và Internet cho lần tải Maven/dependency/image đầu tiên. Wrapper giúp không cần cài Maven riêng.

PowerShell, chạy từ thư mục project:

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
# Điền DB_PASSWORD và INTERNAL_*_KEY cần sử dụng vào .env.
docker compose up -d user-db
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

Linux/macOS:

```sh
test -f .env || cp .env.example .env
# Điền các giá trị trống trước khi chạy.
docker compose up -d user-db
sh ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Profile `local` đọc `.env` như file Java properties; không dùng `export`, không bọc giá trị bằng dấu nháy. Giữ giá trị là chuỗi đơn giản (key ngẫu nhiên dạng hex), tránh ký tự backslash/interpolation. Biến môi trường thật có ưu tiên cao hơn file. Nếu đổi DB_PORT, cập nhật DB_URL tương ứng. `.env` bị Git ignore.

Nếu dùng PostgreSQL đã cài trên Windows, bỏ bước `docker compose up -d user-db` và dùng credential của server đó trong `.env`. `user_db` trong DB_URL là **tên database**; DB_USERNAME phải là **user/role đăng nhập thực sự tồn tại**, không tự động là tên database. Ví dụ server local dùng user `postgres` thì đặt `DB_USERNAME=postgres` và mật khẩu của user đó. DB_PORT thường là 5432; SERVER_PORT=8081 là port HTTP của ứng dụng. Sửa `.env.example` không cấu hình ứng dụng: cần file `.env` và phải bật profile `local` bằng lệnh ở trên hoặc cấu hình Active profiles trong IDE.

Khi chạy lại, giữ nguyên `.env` đã điền; không chép `.env.example` đè lên file này. Nếu lỗi `SCRAM-based authentication, but no password was provided`, kiểm tra `DB_PASSWORD` trong `.env` có giá trị, working directory là thư mục project và profile `local` đang bật. Build `package` kiểm tra code/test Web có thể thành công trong khi `spring-boot:run` vẫn lỗi credential: chỉ lệnh chạy ứng dụng mới kết nối DB local. Dùng tham số `-Dspring-boot.run.profiles=local` mỗi lần chạy để không phụ thuộc biến profile của phiên terminal trước.

Tạo mỗi service key bằng ít nhất 32 byte ngẫu nhiên (ví dụ PowerShell dưới đây sinh 64 ký tự hex; tự lưu vào `.env` và cấu hình caller tương ứng):

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[BitConverter]::ToString($bytes).Replace('-', '')
```

Key trống vô hiệu caller; key ngắn hoặc key dùng chung giữa hai caller làm ứng dụng từ chối khởi động. Không có key mẫu production. `DB_PASSWORD` phải được điền; Compose không tự gán mật khẩu. Không đổi credential của volume PostgreSQL đang có chỉ bằng sửa `.env`.

## Chạy cả ứng dụng bằng Compose

Sau khi điền `.env`:

```powershell
docker compose --profile app up -d --build
```

Ứng dụng trong container dùng `user-db:5432`, không dùng localhost cho DB. PostgreSQL và port 8081 chỉ bind `127.0.0.1` của host; DB có named volume riêng. Đây là cấu hình local, không phải deployment public. Deployment cần private network/HTTPS và Gateway chặn `/internal/**`; các thay đổi Gateway thuộc repo khác.

## Endpoint hiện có

```text
GET http://localhost:8081/actuator/health
GET http://localhost:8081/actuator/health/liveness
GET http://localhost:8081/actuator/health/readiness
GET http://localhost:8081/internal/v1/accounts/{UUID}/access-state
X-Service-Key: <key riêng của caller đã cấu hình>
```

Access-state response 200:

```json
{"accountId":"42c4b826-6633-4636-8e2f-13638973c3d9","status":"ACTIVE","role":"FARMER","accessAllowed":true}
```

UNVERIFIED/LOCKED/INACTIVE vẫn trả **200 với `accessAllowed:false`** cho caller nội bộ; Gateway/backend chịu trách nhiệm chuyển protected request thành 403. Thiếu/sai key: 401 UNAUTHORIZED; key không cấp quyền endpoint/method khác: 403 FORBIDDEN; UUID sai: 400 VALIDATION_ERROR; account không tồn tại: 404 ACCOUNT_NOT_FOUND; DB lỗi: 503 ACCESS_STATE_UNAVAILABLE. `X-User-Id`, `X-User-Role`, Bearer token không thay service key. Không có tài khoản seed trong giai đoạn này, nên một UUID chưa được tạo sẽ trả 404.

Caller phải đặt **timeout tổng 1 giây** và fail closed khi lỗi/timeout; server đặt JDBC query timeout 1 giây và pool acquisition timeout 500ms, không coi chúng là bảo đảm tổng thời gian response luôn dưới 1 giây. DB_URL phải trỏ primary, không read replica. Không dùng issuer discovery cho URN, không có JWKS endpoint.

## Build và kiểm thử

```powershell
.\mvnw.cmd verify
```

`verify` chạy test Web/security rồi integration test PostgreSQL thật qua Testcontainers (`postgres:17-alpine`), migrate bằng Flyway, khởi động Spring context và kiểm role/profile/unique/score, row locking, quota cleanup, access-state sau commit và health. Không tự bỏ qua IT nếu Docker thiếu; lệnh sẽ báo lỗi rõ ràng. Không dùng H2 thay PostgreSQL.

Chỉ chạy test Web/security và đóng gói khi Docker chưa sẵn sàng:

```powershell
.\mvnw.cmd package
```

`package` không chạy Failsafe IT, do đó không thay thế kết quả `verify`.

Có thể chạy IT với PostgreSQL riêng: phải dùng **database test rỗng, có thể xóa dữ liệu**, vì mỗi test `TRUNCATE accounts CASCADE`; tuyệt đối không trỏ user_db đang dùng. Credential chỉ qua biến môi trường:

```powershell
$env:TEST_DB_PASSWORD = '<password của database test>'
.\mvnw.cmd '-Dtest.db.url=jdbc:postgresql://localhost:5432/user_service_test' '-Dtest.db.username=user_service_test' verify
```

Report: `target/surefire-reports/`, `target/failsafe-reports/`. JAR: `target/user-service-0.1.0-SNAPSHOT.jar`.

Kiểm tra JAR thật ở port 8081 với DB Compose tạm biệt lập (port 8081 phải trống):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Smoke-Local.ps1
```

Script kiểm local profile, health, key 401, account 404, ACTIVE/LOCKED sau commit và 503 khi DB thật dừng. Nó tự dừng JVM và xóa đúng Compose project/volume test sinh ngẫu nhiên; không dùng volume local thông thường. Credential test ngẫu nhiên nằm trong `.tools/` bị ignore, không ghi vào `.env.example`.

## Quyết định và công việc tiếp theo

Xem `docs/implementation-decisions.md` cho đối chiếu BA cũ/v2.1, schema kỹ thuật và phạm vi chưa triển khai. Chỉ DB credential và key của caller cần cấu hình trong giai đoạn này. SMTP, OTP secret, RSA keys/kid, email hai Admin và SUPPORT_EMAIL cần cung cấp cho giai đoạn auth/vận hành sau; chưa dùng credential giả để đánh dấu các luồng đó hoàn tất.
